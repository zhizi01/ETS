package com.github.zhizi01.ets.archive;

import com.github.zhizi01.ets.api.ArchivePort;
import com.github.zhizi01.ets.config.EtsProperties;
import com.github.zhizi01.ets.domain.model.Bill;
import com.github.zhizi01.ets.domain.model.Contract;
import com.github.zhizi01.ets.domain.model.Trade;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 纯 JDBC 归档。不注册 DataSource，连不上 MySQL 时只打日志，Redis 主路径不受影响。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "ets.mysql", name = "enabled", havingValue = "true")
public class MysqlArchiveWriter implements ArchivePort {

    private static final String UPSERT_TRADE = """
            INSERT INTO ets_trade (id, sim_id, session_id, variety, period_type, trade_mode,
              buy_order_id, sell_order_id, buy_account_id, sell_account_id,
              buy_account_code, sell_account_code, price, quantity, amount,
              buy_fee, sell_fee, taker_side, traded_at, seq)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE
              price=VALUES(price), quantity=VALUES(quantity), amount=VALUES(amount),
              buy_fee=VALUES(buy_fee), sell_fee=VALUES(sell_fee), traded_at=VALUES(traded_at)
            """;

    private static final String UPSERT_CONTRACT = """
            INSERT INTO ets_contract (id, sim_id, trade_id, session_id, variety, period_type, trade_mode,
              buy_account_id, buy_account_code, buy_account_name,
              sell_account_id, sell_account_code, sell_account_name,
              price, quantity, amount, delivery_start, delivery_end, status, delivered_qty, signed_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE
              status=VALUES(status), delivered_qty=VALUES(delivered_qty),
              price=VALUES(price), quantity=VALUES(quantity), amount=VALUES(amount)
            """;

    private static final String UPSERT_BILL = """
            INSERT INTO ets_bill (id, sim_id, account_id, account_code, account_name, period,
              contract_qty, contract_amount, actual_qty, deviation_qty, deviation_penalty,
              spot_qty, spot_amount, fee_total, revenue, cost, profit, rank_no, settled_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE
              contract_qty=VALUES(contract_qty), contract_amount=VALUES(contract_amount),
              actual_qty=VALUES(actual_qty), deviation_qty=VALUES(deviation_qty),
              deviation_penalty=VALUES(deviation_penalty), spot_qty=VALUES(spot_qty),
              spot_amount=VALUES(spot_amount), fee_total=VALUES(fee_total),
              revenue=VALUES(revenue), cost=VALUES(cost), profit=VALUES(profit),
              rank_no=VALUES(rank_no), settled_at=VALUES(settled_at)
            """;

    private final EtsProperties.Mysql cfg;
    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>(20_000);
    private volatile boolean reachable;

    public MysqlArchiveWriter(EtsProperties props) {
        this.cfg = props.getMysql();
    }

    @PostConstruct
    public void init() {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            try (Connection c = open()) {
                reachable = c != null && c.isValid(3);
            }
            log.info("MySQL 归档已启用 reachable={}", reachable);
        } catch (Exception e) {
            reachable = false;
            log.warn("MySQL 归档暂不可达，成交仍只写 Redis：{}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        flush();
    }

    @Override
    public void offerTrade(Trade trade) {
        enqueue(trade);
    }

    @Override
    public void offerContract(Contract contract) {
        enqueue(contract);
    }

    @Override
    public void offerBill(Bill bill) {
        enqueue(bill);
    }

    @Scheduled(fixedDelayString = "${ets.mysql.flush-interval-ms:5000}")
    public void scheduledFlush() {
        flush();
    }

    private void enqueue(Object item) {
        if (item == null) {
            return;
        }
        if (!queue.offer(item)) {
            log.warn("归档队列已满，丢弃一条 {}", item.getClass().getSimpleName());
        }
    }

    private synchronized void flush() {
        if (queue.isEmpty()) {
            return;
        }
        List<Object> batch = new ArrayList<>(Math.min(cfg.getBatchSize(), queue.size()));
        queue.drainTo(batch, cfg.getBatchSize());
        if (batch.isEmpty()) {
            return;
        }
        try (Connection conn = open()) {
            if (conn == null) {
                requeue(batch);
                return;
            }
            conn.setAutoCommit(false);
            try {
                writeBatch(conn, batch);
                conn.commit();
                reachable = true;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            reachable = false;
            requeue(batch);
            log.warn("MySQL 归档刷盘失败，已回队 {} 条：{}", batch.size(), e.getMessage());
        }
    }

    private void writeBatch(Connection conn, List<Object> batch) throws SQLException {
        try (PreparedStatement tradePs = conn.prepareStatement(UPSERT_TRADE);
             PreparedStatement contractPs = conn.prepareStatement(UPSERT_CONTRACT);
             PreparedStatement billPs = conn.prepareStatement(UPSERT_BILL)) {
            for (Object item : batch) {
                if (item instanceof Trade t) {
                    bindTrade(tradePs, t);
                    tradePs.addBatch();
                } else if (item instanceof Contract c) {
                    bindContract(contractPs, c);
                    contractPs.addBatch();
                } else if (item instanceof Bill b) {
                    bindBill(billPs, b);
                    billPs.addBatch();
                }
            }
            tradePs.executeBatch();
            contractPs.executeBatch();
            billPs.executeBatch();
        }
    }

    private void bindTrade(PreparedStatement ps, Trade t) throws SQLException {
        int i = 1;
        ps.setString(i++, t.getId());
        ps.setString(i++, t.getSimId());
        ps.setString(i++, t.getSessionId());
        ps.setString(i++, name(t.getVariety()));
        ps.setString(i++, name(t.getPeriodType()));
        ps.setString(i++, name(t.getTradeMode()));
        ps.setString(i++, t.getBuyOrderId());
        ps.setString(i++, t.getSellOrderId());
        ps.setString(i++, t.getBuyAccountId());
        ps.setString(i++, t.getSellAccountId());
        ps.setString(i++, t.getBuyAccountCode());
        ps.setString(i++, t.getSellAccountCode());
        ps.setDouble(i++, t.getPrice());
        ps.setDouble(i++, t.getQuantity());
        ps.setDouble(i++, t.getAmount());
        ps.setDouble(i++, t.getBuyFee());
        ps.setDouble(i++, t.getSellFee());
        ps.setString(i++, name(t.getTakerSide()));
        ps.setLong(i++, t.getTradedAt());
        ps.setLong(i, t.getSeq());
    }

    private void bindContract(PreparedStatement ps, Contract c) throws SQLException {
        int i = 1;
        ps.setString(i++, c.getId());
        ps.setString(i++, c.getSimId());
        ps.setString(i++, c.getTradeId());
        ps.setString(i++, c.getSessionId());
        ps.setString(i++, name(c.getVariety()));
        ps.setString(i++, name(c.getPeriodType()));
        ps.setString(i++, name(c.getTradeMode()));
        ps.setString(i++, c.getBuyAccountId());
        ps.setString(i++, c.getBuyAccountCode());
        ps.setString(i++, c.getBuyAccountName());
        ps.setString(i++, c.getSellAccountId());
        ps.setString(i++, c.getSellAccountCode());
        ps.setString(i++, c.getSellAccountName());
        ps.setDouble(i++, c.getPrice());
        ps.setDouble(i++, c.getQuantity());
        ps.setDouble(i++, c.getAmount());
        ps.setString(i++, c.getDeliveryStart());
        ps.setString(i++, c.getDeliveryEnd());
        ps.setString(i++, name(c.getStatus()));
        ps.setDouble(i++, c.getDeliveredQty());
        ps.setLong(i, c.getSignedAt());
    }

    private void bindBill(PreparedStatement ps, Bill b) throws SQLException {
        int i = 1;
        ps.setString(i++, b.getId());
        ps.setString(i++, b.getSimId());
        ps.setString(i++, b.getAccountId());
        ps.setString(i++, b.getAccountCode());
        ps.setString(i++, b.getAccountName());
        ps.setString(i++, b.getPeriod());
        ps.setDouble(i++, b.getContractQty());
        ps.setDouble(i++, b.getContractAmount());
        ps.setDouble(i++, b.getActualQty());
        ps.setDouble(i++, b.getDeviationQty());
        ps.setDouble(i++, b.getDeviationPenalty());
        ps.setDouble(i++, b.getSpotQty());
        ps.setDouble(i++, b.getSpotAmount());
        ps.setDouble(i++, b.getFeeTotal());
        ps.setDouble(i++, b.getRevenue());
        ps.setDouble(i++, b.getCost());
        ps.setDouble(i++, b.getProfit());
        ps.setInt(i++, b.getRank());
        ps.setLong(i, b.getSettledAt());
    }

    private Connection open() {
        try {
            Connection c = DriverManager.getConnection(cfg.getUrl(), cfg.getUsername(), cfg.getPassword());
            reachable = true;
            return c;
        } catch (SQLException e) {
            reachable = false;
            log.debug("连接 MySQL 失败：{}", e.getMessage());
            return null;
        }
    }

    private void requeue(List<Object> batch) {
        for (int i = batch.size() - 1; i >= 0; i--) {
            if (!queue.offer(batch.get(i))) {
                break;
            }
        }
    }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }
}
