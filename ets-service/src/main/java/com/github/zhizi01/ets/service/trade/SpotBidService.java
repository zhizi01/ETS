package com.github.zhizi01.ets.service.trade;

import com.github.zhizi01.ets.api.AccountPort;
import com.github.zhizi01.ets.api.SimPort;
import com.github.zhizi01.ets.api.WsPort;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.IdGen;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.enums.SpotBidStatus;
import com.github.zhizi01.ets.domain.enums.Variety;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.SpotBid;
import com.github.zhizi01.ets.domain.model.SpotSegment;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.engine.SpotClearingEngine;
import com.github.zhizi01.ets.repo.SpotBidRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 日前现货报价曲线的申报与查询。
 * <p>
 * 资金口径：报价阶段不冻结——24 段曲线的名义敞口远大于真实中标额，
 * 提前全额冻结会把账户锁死。资金/保证金检查改到出清成交那一刻做（见 SpotClearingEngine）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpotBidService {

    private final SpotBidRepo spotBidRepo;
    private final RiskService riskService;
    private final IdGen idGen;

    @Lazy
    @Autowired
    private SimPort simPort;
    @Lazy
    @Autowired
    private AccountPort accountPort;
    @Lazy
    @Autowired
    private WsPort wsPort;

    /** 提交 24 点报价曲线。同账户同场次重复提交视为覆盖（沿用原 id，索引不会残留）。 */
    public SpotBid submit(String accountId, String sessionId, Side side, String tradeDate,
                          List<SpotSegment> segments, OrderSource source, String userId) {
        TradingSession sess = simPort.requireSession(sessionId);
        Sim sim = simPort.requireSim(sess.getSimId());
        TradingAccount acct = accountPort.require(accountId);

        riskService.checkSimRunning(sim);
        riskService.checkSessionAcceptsOrders(sess);
        riskService.checkAccountTradable(acct, sess);
        riskService.checkOwnership(acct, userId);
        if (sess.getVariety() != Variety.SPOT_DA) {
            throw BizException.of(ErrorCode.SESSION_STATE_ILLEGAL, "该场次不是日前现货场次");
        }
        if (side == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "买卖方向不能为空");
        }

        List<SpotSegment> normalized = normalize(segments, sess, sim);
        double totalQty = 0;
        for (SpotSegment s : normalized) {
            totalQty += s.getQuantity();
        }
        if (side == Side.SELL) {
            riskService.checkSellableEnergy(acct, Nums.qty(totalQty), sim);
        }

        SpotBid bid = existing(sessionId, accountId);
        if (bid == null) {
            bid = new SpotBid();
            bid.setId(idGen.next(IdGen.SPOT_BID));
            bid.setCreatedAt(System.currentTimeMillis());
        }
        bid.setSimId(sess.getSimId());
        bid.setSessionId(sessionId);
        bid.setAccountId(acct.getId());
        bid.setAccountCode(acct.getCode());
        bid.setUserId(acct.getUserId());
        bid.setSource(source == null ? OrderSource.HUMAN : source);
        bid.setSide(side);
        bid.setTradeDate(tradeDate != null && !tradeDate.isBlank() ? tradeDate : sess.getDeliveryStart());
        bid.setSegments(normalized);
        bid.setStatus(SpotBidStatus.ACTIVE);
        bid.setClearedQty(0);
        bid.setClearedAmount(0);
        bid.setUpdatedAt(System.currentTimeMillis());
        spotBidRepo.saveBid(bid);
        safePush("order." + acct.getId(), bid);
        return bid;
    }

    public void cancel(String bidId, String accountId) {
        SpotBid bid = spotBidRepo.require(bidId, ErrorCode.SPOT_BID_NOT_FOUND);
        if (accountId != null && !accountId.equals(bid.getAccountId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只能撤销自己的现货报价");
        }
        if (bid.getStatus() != SpotBidStatus.ACTIVE) {
            throw BizException.of(ErrorCode.SESSION_STATE_ILLEGAL,
                    "该报价当前为「" + bid.getStatus().getLabel() + "」，不可撤销");
        }
        TradingSession sess = simPort.requireSession(bid.getSessionId());
        if (!sess.getState().allowsCancel()) {
            throw BizException.of(ErrorCode.SESSION_STATE_ILLEGAL,
                    "场次当前为" + sess.getState().getLabel() + "，不允许撤销报价");
        }
        bid.setStatus(SpotBidStatus.CANCELLED);
        spotBidRepo.update(bid);
        safePush("order." + bid.getAccountId(), bid);
    }

    public List<SpotBid> query(String accountId, String sessionId) {
        List<SpotBid> out = new ArrayList<>();
        for (SpotBid b : spotBidRepo.listBySession(sessionId)) {
            if (b == null || b.getStatus() == SpotBidStatus.CANCELLED) {
                continue;
            }
            if (accountId != null && !accountId.isBlank() && !accountId.equals(b.getAccountId())) {
                continue;
            }
            out.add(b);
        }
        return out;
    }

    public List<Map<String, Object>> clearingResult(String sessionId) {
        return spotBidRepo.clearingResult(sessionId);
    }

    public SpotBid require(String bidId) {
        return spotBidRepo.require(bidId, ErrorCode.SPOT_BID_NOT_FOUND);
    }

    public SpotBid myBid(String accountId, String sessionId) {
        for (SpotBid b : spotBidRepo.listBySession(sessionId)) {
            if (b != null && accountId.equals(b.getAccountId()) && b.getStatus() != SpotBidStatus.CANCELLED) {
                return b;
            }
        }
        return null;
    }

    // ── 内部 ────────────────────────────────────────────────

    /** 曲线必须严格覆盖 0~23，且每段价格在场次限价内。 */
    private List<SpotSegment> normalize(List<SpotSegment> segments, TradingSession sess, Sim sim) {
        if (segments == null || segments.size() != SpotClearingEngine.HOURS) {
            throw BizException.of(ErrorCode.SPOT_SEGMENT_ILLEGAL,
                    "现货报价曲线必须提供 24 段，当前 " + (segments == null ? 0 : segments.size()) + " 段");
        }
        SpotSegment[] slots = new SpotSegment[SpotClearingEngine.HOURS];
        for (SpotSegment s : segments) {
            int h = s.getHour();
            if (h < 0 || h >= SpotClearingEngine.HOURS) {
                throw BizException.of(ErrorCode.SPOT_SEGMENT_ILLEGAL, "小时段必须在 0~23 之间，收到 " + h);
            }
            if (slots[h] != null) {
                throw BizException.of(ErrorCode.SPOT_SEGMENT_ILLEGAL, "第 " + h + " 小时段重复申报");
            }
            SpotSegment n = new SpotSegment(h,
                    riskService.normalizePrice(s.getPrice(), sess),
                    Nums.qty(Math.max(s.getQuantity(), 0)));
            if (Nums.isPositive(n.getQuantity())) {
                riskService.checkPrice(n.getPrice(), sess, sim);
            }
            slots[h] = n;
        }
        List<SpotSegment> out = new ArrayList<>(SpotClearingEngine.HOURS);
        for (int h = 0; h < SpotClearingEngine.HOURS; h++) {
            if (slots[h] == null) {
                throw BizException.of(ErrorCode.SPOT_SEGMENT_ILLEGAL, "缺少第 " + h + " 小时段的报价");
            }
            out.add(slots[h]);
        }
        return out;
    }

    private SpotBid existing(String sessionId, String accountId) {
        for (SpotBid b : spotBidRepo.listBySession(sessionId)) {
            if (b != null && accountId.equals(b.getAccountId()) && b.getStatus() == SpotBidStatus.ACTIVE) {
                return b;
            }
        }
        return null;
    }

    private void safePush(String topic, Object data) {
        try {
            wsPort.push(topic, data);
        } catch (Exception ignored) {
            // 推送失败不影响申报
        }
    }
}
