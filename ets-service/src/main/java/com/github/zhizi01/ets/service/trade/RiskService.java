package com.github.zhizi01.ets.service.trade;

import com.github.zhizi01.ets.api.FundPort;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.enums.SimState;
import com.github.zhizi01.ets.domain.model.AccountParams;
import com.github.zhizi01.ets.domain.model.Order;
import com.github.zhizi01.ets.domain.model.Position;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.repo.PositionRepo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 下单前风控，严格对应 DESIGN.md 7.6。任一项不通过即抛 BizException，msg 为中文原因。
 * <p>
 * 冻结约定：订单的 frozenAmount 始终表示「该单当前仍被占用的预留」——
 * 买单是货款含手续费，卖单是保证金。成交时按成交比例释放，撤单/闭市时释放余额。
 * 释放必须发生在 FundPort.applyTrade 之前，因为 applyTrade 按可用资金扣款。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskService {

    private final PositionRepo positionRepo;

    @Lazy
    @Autowired
    private FundPort fundPort;

    // ── 场景校验 ────────────────────────────────────────────

    public void checkSimRunning(Sim sim) {
        if (sim == null) {
            throw BizException.of(ErrorCode.SIM_NOT_FOUND);
        }
        if (sim.getState() != SimState.RUNNING) {
            throw BizException.of(ErrorCode.SIM_NOT_RUNNING,
                    "模拟盘当前为「" + sim.getState().getLabel() + "」，不能交易");
        }
    }

    public void checkSessionAcceptsOrders(TradingSession sess) {
        if (sess == null) {
            throw BizException.of(ErrorCode.SESSION_NOT_FOUND);
        }
        if (!sess.getState().acceptsOrders()) {
            throw BizException.of(ErrorCode.SESSION_STATE_ILLEGAL,
                    "场次「" + sess.getName() + "」当前为" + sess.getState().getLabel() + "，不接受申报");
        }
    }

    public void checkAccountTradable(TradingAccount acct, TradingSession sess) {
        if (acct == null) {
            throw BizException.of(ErrorCode.ACCOUNT_NOT_FOUND);
        }
        if (acct.isFrozen()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "该交易账户已被冻结，无法交易");
        }
        if (!acct.isParamsLocked()) {
            throw BizException.of(ErrorCode.SIM_NOT_RUNNING, "账户参数尚未锁定，请等待管理员开盘");
        }
        if (sess != null && acct.getSimId() != null && !acct.getSimId().equals(sess.getSimId())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "交易账户与交易场次不属于同一个模拟盘");
        }
    }

    /** 归属校验。userId 为 null 表示机器人下单，跳过。 */
    public void checkOwnership(TradingAccount acct, String userId) {
        if (userId == null) {
            return;
        }
        if (acct.getUserId() == null || !userId.equals(acct.getUserId())) {
            throw BizException.of(ErrorCode.ACCOUNT_NOT_OWNED);
        }
    }

    // ── 价格与电量 ──────────────────────────────────────────

    /** 价格先对齐到最小变动价位再校验，避免用户输入 380.007 这类无效报价被直接打回。 */
    public double normalizePrice(double price, TradingSession sess) {
        return Nums.alignTick(price, sess.getTickSize());
    }

    public void checkPrice(double price, TradingSession sess, Sim sim) {
        if (!Nums.isPositive(price)) {
            throw BizException.of(ErrorCode.PRICE_OUT_OF_RANGE, "申报价格必须大于 0");
        }
        if (!Nums.gte(price, sess.getPriceFloor()) || !Nums.gte(sess.getPriceCap(), price)) {
            throw BizException.of(ErrorCode.PRICE_OUT_OF_RANGE,
                    "申报价格须在 " + Nums.price(sess.getPriceFloor()) + " ~ "
                            + Nums.price(sess.getPriceCap()) + " 元/MWh 之间");
        }
        double prevClose = sess.getPrevClose();
        if (Nums.isPositive(prevClose)) {
            double pct = sim.getCfg().getPriceLimitPct();
            double lo = Nums.price(prevClose * (1 - pct));
            double hi = Nums.price(prevClose * (1 + pct));
            if (!Nums.gte(price, lo) || !Nums.gte(hi, price)) {
                throw BizException.of(ErrorCode.PRICE_OUT_OF_RANGE,
                        "申报价格超出涨跌幅限制，允许区间 " + lo + " ~ " + hi + " 元/MWh（前收 " + Nums.price(prevClose) + "）");
            }
        }
    }

    public void checkQuantity(double qty, TradingSession sess, TradingAccount acct) {
        if (!Nums.isPositive(qty)) {
            throw BizException.of(ErrorCode.QTY_OUT_OF_RANGE, "申报电量必须大于 0");
        }
        if (!Nums.gte(qty, sess.getMinQty()) || !Nums.gte(sess.getMaxQty(), qty)) {
            throw BizException.of(ErrorCode.QTY_OUT_OF_RANGE,
                    "申报电量须在 " + Nums.qty(sess.getMinQty()) + " ~ " + Nums.qty(sess.getMaxQty()) + " MWh 之间");
        }
        double lot = sess.getLotSize();
        if (lot > 0 && Math.abs(qty / lot - Math.round(qty / lot)) > 1e-6) {
            throw BizException.of(ErrorCode.QTY_OUT_OF_RANGE,
                    "申报电量必须是最小交易单位 " + Nums.qty(lot) + " MWh 的整数倍");
        }
        AccountParams pa = acct.getParams();
        if (pa != null && pa.getMaxSingleOrderMwh() != null && Nums.gt(qty, pa.getMaxSingleOrderMwh())) {
            throw BizException.of(ErrorCode.SINGLE_ORDER_LIMIT_EXCEEDED,
                    "单笔申报电量上限为 " + Nums.qty(pa.getMaxSingleOrderMwh()) + " MWh");
        }
    }

    /** 按标的净持仓做上限校验：减仓方向永远放行，只拦截会把敞口做大的申报。 */
    public void checkPositionLimit(TradingAccount acct, String instrumentKey, Side side, double qty) {
        AccountParams pa = acct.getParams();
        if (pa == null || pa.getMaxPositionMwh() == null || pa.getMaxPositionMwh() <= 0) {
            return;
        }
        Position pos = positionRepo.find(acct.getId(), instrumentKey);
        double net = pos == null ? 0 : pos.getNetQty();
        double projected = side == Side.BUY ? net + qty : net - qty;
        if (Nums.gt(Math.abs(projected), pa.getMaxPositionMwh()) && Math.abs(projected) > Math.abs(net)) {
            throw BizException.of(ErrorCode.POSITION_LIMIT_EXCEEDED,
                    "成交后持仓将达 " + Nums.qty(Math.abs(projected)) + " MWh，超出账户限额 "
                            + Nums.qty(pa.getMaxPositionMwh()) + " MWh");
        }
    }

    /**
     * 可售电量校验。发电企业以年度可发电量为底，其余主体只能转售已购入的电量。
     * allowNakedShort=true 时整项跳过。
     */
    public void checkSellableEnergy(TradingAccount acct, double qty, Sim sim) {
        if (sim.getCfg().isAllowNakedShort()) {
            return;
        }
        AccountParams pa = acct.getParams();
        double base = pa == null || pa.getAnnualEnergyMwh() == null ? 0 : pa.getAnnualEnergyMwh();
        double sellable = Nums.qty(base + acct.boughtMwh() - acct.soldMwh());
        if (Nums.gt(qty, sellable)) {
            throw BizException.of(ErrorCode.INSUFFICIENT_ENERGY,
                    "可售电量不足，当前剩余 " + Nums.qty(Math.max(sellable, 0)) + " MWh，本次申报 " + Nums.qty(qty) + " MWh");
        }
    }

    // ── 资金冻结 ────────────────────────────────────────────

    public double buyReserve(double price, double qty, Sim sim) {
        return Nums.amount(price * qty * (1 + sim.getCfg().getFeeRateBuy()));
    }

    public double sellReserve(double price, double qty, Sim sim) {
        return Nums.amount(price * qty * sim.getCfg().getMarginRatio());
    }

    /** 冻结买方货款（含手续费），失败抛 INSUFFICIENT_FUND。返回冻结金额。 */
    public double freezeBuy(TradingAccount acct, double price, double qty, Sim sim, String refId) {
        double amount = buyReserve(price, qty, sim);
        if (!fundPort.freezeForBuy(acct.getId(), amount, refId)) {
            throw BizException.of(ErrorCode.INSUFFICIENT_FUND,
                    "可用资金不足，本次需冻结 " + Nums.amount(amount) + " 元");
        }
        return amount;
    }

    /** 冻结卖方保证金，失败抛 INSUFFICIENT_FUND。返回冻结金额。 */
    public double freezeSell(TradingAccount acct, double price, double qty, Sim sim, String refId) {
        double margin = sellReserve(price, qty, sim);
        if (!Nums.isPositive(margin)) {
            return 0d;
        }
        if (!fundPort.freezeMargin(acct.getId(), margin, refId)) {
            throw BizException.of(ErrorCode.INSUFFICIENT_FUND,
                    "保证金不足，本次需冻结 " + Nums.amount(margin) + " 元");
        }
        return margin;
    }

    /**
     * 下单前全量风控 + 冻结。通过后 order.frozenAmount 已被写入。
     * 顺序刻意把「只读校验」全部前置，冻结放最后，避免中途失败留下悬空的冻结资金。
     */
    public void validateAndFreeze(Order order, TradingAccount acct, TradingSession sess, Sim sim) {
        checkSimRunning(sim);
        checkSessionAcceptsOrders(sess);
        checkAccountTradable(acct, sess);
        checkOwnership(acct, order.getUserId());

        order.setPrice(normalizePrice(order.getPrice(), sess));
        order.setQuantity(Nums.qty(order.getQuantity()));
        checkPrice(order.getPrice(), sess, sim);
        checkQuantity(order.getQuantity(), sess, acct);
        checkPositionLimit(acct, sess.instrumentKey(), order.getSide(), order.getQuantity());

        if (order.getSide() == Side.BUY) {
            order.setFrozenAmount(freezeBuy(acct, order.getPrice(), order.getQuantity(), sim, order.getId()));
        } else {
            checkSellableEnergy(acct, order.getQuantity(), sim);
            order.setFrozenAmount(freezeSell(acct, order.getPrice(), order.getQuantity(), sim, order.getId()));
        }
    }

    /**
     * 成交时按比例释放该单的预留，让资金回到可用状态供 applyTrade 扣款。
     * 买单若以更优价格成交，差额会留在 frozenAmount 里，最终随撤单/闭市一起退还。
     */
    public void releaseForFill(Order order, double filledQty, Sim sim) {
        if (!Nums.isPositive(filledQty)) {
            return;
        }
        double release;
        // 撮合在订单簿锁内串行，但资金提交在锁外，同一笔挂单可能被两个线程先后提交，
        // frozenAmount 的读-改-写必须以订单实例为锁串行，否则会漏放冻结。
        synchronized (order) {
            if (!Nums.isPositive(order.getFrozenAmount())) {
                return;
            }
            double perFill = order.getSide() == Side.BUY
                    ? buyReserve(order.getPrice(), filledQty, sim)
                    : sellReserve(order.getPrice(), filledQty, sim);
            release = Nums.isPositive(order.remainQty())
                    ? Nums.amount(Math.min(perFill, order.getFrozenAmount()))
                    // 全额成交：逐笔取整留下的零头一并退还，否则会永远冻在账上
                    : order.getFrozenAmount();
            order.setFrozenAmount(Nums.amount(order.getFrozenAmount() - release));
        }
        doRelease(order.getAccountId(), order.getSide(), release, order.getId());
    }

    /** 撤单 / 闭市 / 出清落选时退还该单剩余预留。 */
    public void releaseRemaining(Order order) {
        double left;
        synchronized (order) {
            left = order.getFrozenAmount();
            if (!Nums.isPositive(left)) {
                return;
            }
            order.setFrozenAmount(0);
        }
        doRelease(order.getAccountId(), order.getSide(), left, order.getId());
    }

    public void release(String accountId, Side side, double amount, String refId) {
        doRelease(accountId, side, amount, refId);
    }

    private void doRelease(String accountId, Side side, double amount, String refId) {
        if (!Nums.isPositive(amount)) {
            return;
        }
        try {
            if (side == Side.BUY) {
                fundPort.unfreezeBuy(accountId, amount, refId);
            } else {
                fundPort.releaseMargin(accountId, amount, refId);
            }
        } catch (Exception e) {
            log.error("释放冻结资金失败 acct={} side={} amount={}", accountId, side, amount, e);
        }
    }
}
