package com.github.zhizi01.ets.engine;

import com.github.zhizi01.ets.api.AccountPort;
import com.github.zhizi01.ets.api.CreateListingCmd;
import com.github.zhizi01.ets.api.SimPort;
import com.github.zhizi01.ets.api.WsPort;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.IdGen;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.ListingStatus;
import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.model.Contract;
import com.github.zhizi01.ets.domain.model.Listing;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.TakeOrder;
import com.github.zhizi01.ets.domain.model.Trade;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.repo.ListingRepo;
import com.github.zhizi01.ets.repo.RedisStore;
import com.github.zhizi01.ets.repo.TakeOrderRepo;
import com.github.zhizi01.ets.service.clearing.ContractService;
import com.github.zhizi01.ets.service.trade.RiskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 挂牌摘牌。挂牌方 side=SELL 表示挂牌售电、BUY 表示挂牌购电，成交价恒等于挂牌价。
 * <p>
 * 并发一致性：摘牌的「校验 + 扣减 remainQty + 成交」是一个不可分割的临界区，
 * 全部包在 Redis 分布式锁 ets:lock:listing:{id} 内，抢不到锁直接返回 CONCURRENT_BUSY，
 * 避免两个摘牌方同时把同一份剩余电量摘走。
 * <p>
 * 冻结口径：挂牌方的预留不单独存字段，恒等于 reserve(price, remainQty)——
 * 因为挂牌价固定，remainQty 递减多少就该释放多少，天然自洽。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListingEngine {

    private static final Duration LOCK_TTL = Duration.ofSeconds(3);
    private static final long LOCK_WAIT_MS = 1000;

    private final ListingRepo listingRepo;
    private final TakeOrderRepo takeOrderRepo;
    private final RedisStore store;
    private final RiskService riskService;
    private final ContractService contractService;
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

    // ── 挂牌 ────────────────────────────────────────────────

    public Listing createListing(CreateListingCmd cmd) {
        TradingSession sess = simPort.requireSession(cmd.getSessionId());
        Sim sim = simPort.requireSim(sess.getSimId());
        TradingAccount acct = accountPort.require(cmd.getAccountId());

        riskService.checkSimRunning(sim);
        riskService.checkSessionAcceptsOrders(sess);
        riskService.checkAccountTradable(acct, sess);
        riskService.checkOwnership(acct, cmd.getUserId());

        double price = riskService.normalizePrice(cmd.getPrice(), sess);
        double totalQty = Nums.qty(cmd.getTotalQty());
        riskService.checkPrice(price, sess, sim);
        riskService.checkQuantity(totalQty, sess, acct);
        riskService.checkPositionLimit(acct, sess.instrumentKey(), cmd.getSide(), totalQty);

        double minTake = Nums.qty(cmd.getMinTakeQty() > 0 ? cmd.getMinTakeQty() : sess.getLotSize());
        if (Nums.gt(minTake, totalQty)) {
            throw BizException.of(ErrorCode.LISTING_QTY_ILLEGAL, "最小摘牌电量不能大于挂牌总量");
        }

        Listing l = new Listing();
        l.setId(idGen.next(IdGen.LISTING));
        l.setSimId(sess.getSimId());
        l.setSessionId(sess.getId());
        l.setAccountId(acct.getId());
        l.setAccountCode(acct.getCode());
        l.setAccountName(acct.getName());
        l.setUserId(acct.getUserId());
        l.setSource(cmd.getSource() == null ? OrderSource.HUMAN : cmd.getSource());
        l.setVariety(sess.getVariety());
        l.setPeriodType(sess.getPeriodType());
        l.setSide(cmd.getSide());
        l.setPrice(price);
        l.setTotalQty(totalQty);
        l.setRemainQty(totalQty);
        l.setMinTakeQty(minTake);
        l.setAllowPartial(cmd.isAllowPartial());
        l.setValidUntil(cmd.getValidUntil() > 0 ? cmd.getValidUntil() : sess.getCloseTime());
        l.setStatus(ListingStatus.LISTED);
        l.setTitle(cmd.getTitle());
        l.setRemark(cmd.getRemark());
        l.setCreatedAt(System.currentTimeMillis());
        l.setUpdatedAt(l.getCreatedAt());

        if (cmd.getSide() == Side.BUY) {
            riskService.freezeBuy(acct, price, totalQty, sim, l.getId());
        } else {
            riskService.checkSellableEnergy(acct, totalQty, sim);
            riskService.freezeSell(acct, price, totalQty, sim, l.getId());
        }

        listingRepo.saveListing(l);
        pushListing(l);
        return l;
    }

    // ── 撤牌 / 过期 ─────────────────────────────────────────

    public void withdrawListing(String listingId, String accountId) {
        Listing l = listingRepo.require(listingId, ErrorCode.LISTING_NOT_FOUND);
        if (accountId != null && !accountId.equals(l.getAccountId())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "只能撤销自己发布的挂牌");
        }
        if (!l.getStatus().isTakeable()) {
            throw BizException.of(ErrorCode.LISTING_NOT_AVAILABLE,
                    "挂牌当前为「" + l.getStatus().getLabel() + "」，不能撤牌");
        }
        closeListing(l, ListingStatus.WITHDRAWN);
    }

    /** 场次闭市或挂牌到期时统一收尾，释放挂牌方剩余预留。 */
    public void closeListing(Listing l, ListingStatus finalStatus) {
        Sim sim = simPort.findSim(l.getSimId());
        if (sim != null) {
            releaseListingReserve(l, l.getRemainQty(), 0, sim);
        }
        l.setStatus(finalStatus);
        l.setRemainQty(0);
        listingRepo.update(l);
        pushListing(l);
    }

    // ── 摘牌 ────────────────────────────────────────────────

    public TakeOrder takeListing(String listingId, String accountId, double quantity, OrderSource source) {
        TakeOrder result = store.withLock("listing:" + listingId, LOCK_TTL, LOCK_WAIT_MS,
                () -> doTake(listingId, accountId, quantity, source));
        if (result == null) {
            throw BizException.of(ErrorCode.CONCURRENT_BUSY, "该挂牌正在被其他主体摘取，请稍后重试");
        }
        return result;
    }

    private TakeOrder doTake(String listingId, String accountId, double quantity, OrderSource source) {
        // 必须在锁内重新读取，锁外读到的 remainQty 可能已经过期
        Listing l = listingRepo.require(listingId, ErrorCode.LISTING_NOT_FOUND);
        TradingSession sess = simPort.requireSession(l.getSessionId());
        Sim sim = simPort.requireSim(l.getSimId());
        TradingAccount taker = accountPort.require(accountId);
        TradingAccount lister = accountPort.require(l.getAccountId());

        if (accountId.equals(l.getAccountId())) {
            throw BizException.of(ErrorCode.LISTING_SELF_TAKE);
        }
        if (!l.getStatus().isTakeable()) {
            throw BizException.of(ErrorCode.LISTING_NOT_AVAILABLE,
                    "挂牌当前为「" + l.getStatus().getLabel() + "」，不可摘牌");
        }
        long now = System.currentTimeMillis();
        if (l.expired(now)) {
            closeListing(l, ListingStatus.EXPIRED);
            throw BizException.of(ErrorCode.LISTING_NOT_AVAILABLE, "该挂牌已过有效期");
        }
        riskService.checkSimRunning(sim);
        riskService.checkSessionAcceptsOrders(sess);
        riskService.checkAccountTradable(taker, sess);

        double qty = Nums.qty(quantity);
        if (!Nums.gte(qty, l.getMinTakeQty())) {
            throw BizException.of(ErrorCode.LISTING_QTY_ILLEGAL,
                    "单次摘牌电量不得低于 " + Nums.qty(l.getMinTakeQty()) + " MWh");
        }
        if (!Nums.gte(l.getRemainQty(), qty)) {
            throw BizException.of(ErrorCode.LISTING_QTY_ILLEGAL,
                    "摘牌电量超过挂牌剩余量 " + Nums.qty(l.getRemainQty()) + " MWh");
        }
        if (!l.isAllowPartial() && !Nums.eq(qty, l.getRemainQty())) {
            throw BizException.of(ErrorCode.LISTING_QTY_ILLEGAL,
                    "该挂牌不支持部分摘牌，必须整量摘取 " + Nums.qty(l.getRemainQty()) + " MWh");
        }

        Side takerSide = l.getSide().opposite();
        riskService.checkPositionLimit(taker, sess.instrumentKey(), takerSide, qty);

        // 摘牌方先冻结再立即释放：借 FundPort 的原子冻结做一次「资金/保证金是否够」的校验
        String ref = "TAKE:" + l.getId();
        double takerReserve;
        if (takerSide == Side.BUY) {
            takerReserve = riskService.freezeBuy(taker, l.getPrice(), qty, sim, ref);
        } else {
            riskService.checkSellableEnergy(taker, qty, sim);
            takerReserve = riskService.freezeSell(taker, l.getPrice(), qty, sim, ref);
        }
        riskService.release(taker.getId(), takerSide, takerReserve, ref);
        double remainAfter = Nums.qty(l.getRemainQty() - qty);
        releaseListingReserve(l, l.getRemainQty(), remainAfter, sim);

        TradingAccount buyer = takerSide == Side.BUY ? taker : lister;
        TradingAccount seller = takerSide == Side.BUY ? lister : taker;

        TakeOrder to = new TakeOrder();
        to.setId(idGen.next(IdGen.TAKE));
        to.setListingId(l.getId());
        to.setSimId(l.getSimId());
        to.setSessionId(l.getSessionId());
        to.setAccountId(taker.getId());
        to.setAccountCode(taker.getCode());
        to.setUserId(taker.getUserId());
        to.setSource(source == null ? OrderSource.HUMAN : source);
        to.setQuantity(qty);
        to.setPrice(l.getPrice());
        to.setAmount(Nums.amount(l.getPrice() * qty));
        to.setCreatedAt(now);

        // 成交双方的凭证不同：主动方指向摘牌单，被动方指向挂牌单
        String buyRef = takerSide == Side.BUY ? to.getId() : l.getId();
        String sellRef = takerSide == Side.BUY ? l.getId() : to.getId();
        Trade t = contractService.newTrade(sim, l.getSessionId(), l.getVariety(), l.getPeriodType(),
                sess.getTradeMode(), buyRef, sellRef, buyer, seller, l.getPrice(), qty, takerSide);
        Contract contract = contractService.commitTrade(t, sess.getDeliveryStart(), sess.getDeliveryEnd());

        to.setTradeId(t.getId());
        to.setContractId(contract == null ? null : contract.getId());
        to.setFee(takerSide == Side.BUY ? t.getBuyFee() : t.getSellFee());
        takeOrderRepo.saveTake(to);

        l.setRemainQty(remainAfter);
        l.setStatus(Nums.isPositive(remainAfter) ? ListingStatus.PARTIAL_TAKEN : ListingStatus.FULLY_TAKEN);
        listingRepo.update(l);

        pushListing(l);
        safePush("order." + taker.getId(), to);
        safePush("order." + lister.getId(), l);
        log.info("摘牌成交 listing={} taker={} qty={} price={}", l.getId(), taker.getId(), qty, l.getPrice());
        return to;
    }

    // ── 工具 ────────────────────────────────────────────────

    /**
     * 按「摘牌前剩余量的预留 − 摘牌后剩余量的预留」释放，多次部分摘牌的释放额可以精确累加回总冻结额，
     * 不会因为每次单独取整而留下退不掉的零头。
     */
    private void releaseListingReserve(Listing l, double remainBefore, double remainAfter, Sim sim) {
        double amount = Nums.amount(reserveOf(l, remainBefore, sim) - reserveOf(l, remainAfter, sim));
        if (Nums.isPositive(amount)) {
            riskService.release(l.getAccountId(), l.getSide(), amount, l.getId());
        }
    }

    private double reserveOf(Listing l, double qty, Sim sim) {
        if (!Nums.isPositive(qty)) {
            return 0d;
        }
        return l.getSide() == Side.BUY
                ? riskService.buyReserve(l.getPrice(), qty, sim)
                : riskService.sellReserve(l.getPrice(), qty, sim);
    }

    private void pushListing(Listing l) {
        safePush("listing." + l.getSimId() + "." + l.instrumentKey(), l);
    }

    private void safePush(String topic, Object data) {
        try {
            wsPort.push(topic, data);
        } catch (Exception ignored) {
            // 推送失败不影响挂牌摘牌主流程
        }
    }
}
