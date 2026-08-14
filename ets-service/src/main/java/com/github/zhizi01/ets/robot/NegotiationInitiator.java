package com.github.zhizi01.ets.robot;

import com.github.zhizi01.ets.api.TradePort;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.model.Negotiation;
import com.github.zhizi01.ets.domain.model.Robot;
import com.github.zhizi01.ets.domain.model.RobotConfig;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.repo.NegotiationRepo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 机器人主动发起双边协商邀约。
 * <p>
 * 协商工作台的价值在于「有人找上门」，所以这里优先把邀约投给真人账户——
 * 用户一登录就能看到待回应的邀约，而不是面对一张空表。
 * <p>
 * 频控是这个模块最要紧的部分：邀约是有状态的会话，刷屏会让工作台完全不可用。
 * 三道闸门：单台机器人的最小发起间隔、自己在途邀约总数、对同一对手方的在途邀约数。
 */
@Slf4j
@Component
public class NegotiationInitiator {

    /** 同一台机器人两次发起之间的最小间隔 */
    public static final long MIN_INTERVAL_MS = 60_000;
    /** 单台机器人同时挂着的在途邀约上限 */
    private static final int MAX_INFLIGHT = 4;
    /** 单台机器人对同一个对手方的在途邀约上限 */
    private static final int MAX_INFLIGHT_PER_PARTY = 2;
    /**
     * 一个对手方待回应的邀约总数上限（不分来自哪台机器人）。
     * 只靠上面的「单机器人对单对手」限额挡不住刷屏：真人往往是全场唯一的人类账户，
     * 24 台机器人各发 2 笔就是 48 条待回应邀约，工作台会直接不可用。
     */
    private static final int MAX_INFLIGHT_TO_TARGET = 6;
    /** 有真人在场时把邀约投给真人的概率，剩余概率留给机器人之间互相协商，盘面才不会只有真人一条线 */
    private static final double PREFER_HUMAN_P = 0.8;

    private final TradePort tradePort;
    private final NegotiationRepo negotiationRepo;

    public NegotiationInitiator(@Lazy TradePort tradePort, NegotiationRepo negotiationRepo) {
        this.tradePort = tradePort;
        this.negotiationRepo = negotiationRepo;
    }

    /** 机器人报价合规化能力，由 RobotRunner 提供，避免这里重复一套限价与风控口径 */
    public interface Limits {
        double fixPrice(double raw);

        double fixQty(double raw);

        double capacity(Side side, double price);
    }

    /**
     * 尝试发起一次邀约。
     *
     * @param peers 该模拟盘下的全部交易账户（含真人与机器人）
     * @return 成功时返回可展示的动作描述，未发起时返回 null
     */
    public String propose(Robot robot, TradingAccount me, MemberType mt, TradingSession sess,
                          List<TradingAccount> peers, RobotConfig cfg, Random rnd,
                          double ref, Limits limits) {
        if (ref <= 0 || peers == null || peers.isEmpty()) {
            return null;
        }
        Side mySide = sideOf(mt, rnd);
        List<Negotiation> mine = safeList(me.getId());
        if (countInflightInitiated(mine, me.getId()) >= MAX_INFLIGHT) {
            return null;
        }

        TradingAccount target = pickCounterparty(me, mySide, peers, mine, rnd);
        if (target == null) {
            return null;
        }

        // 开价对自己有利但留出还价空间：卖方高报 1%~4%，买方低报 1%~4%
        double away = 0.01 + rnd.nextDouble() * 0.03;
        double price = limits.fixPrice(mySide == Side.SELL ? ref * (1 + away) : ref * (1 - away));
        double qty = quantityOf(me, sess, mySide, price, cfg, rnd, limits);
        if (price <= 0 || qty <= 0) {
            return null;
        }

        int expire = expireMinutes(sess, rnd);
        try {
            Negotiation n = tradePort.proposeNegotiation(me.getId(), target.getId(), sess.getId(),
                    mySide, price, qty, message(robot, sess, mySide, price, qty), expire, OrderSource.ROBOT);
            if (n == null) {
                return null;
            }
            log.debug("机器人 {} 向 {} 发起协商 {} {}MWh@{}", robot.getName(), target.getName(), mySide, qty, price);
            return (mySide == Side.BUY ? "邀约购电 " : "邀约售电 ") + Nums.price(price) + " → " + target.getName();
        } catch (BizException e) {
            log.debug("机器人 {} 发起协商被拒: {}", robot.getName(), e.getMessage());
            return null;
        } catch (Exception e) {
            log.debug("机器人 {} 发起协商异常: {}", robot.getName(), e.toString());
            return null;
        }
    }

    // ── 选边与选对手 ────────────────────────────────────────

    /** 发电卖电、用电买电；售电公司两头都做，但以采购为主业 */
    private Side sideOf(MemberType mt, Random rnd) {
        if (mt == MemberType.GENCO) {
            return Side.SELL;
        }
        if (mt == MemberType.CONSUMER) {
            return Side.BUY;
        }
        return rnd.nextDouble() < 0.65 ? Side.BUY : Side.SELL;
    }

    /**
     * 从方向相反的主体里挑对手方：卖方找用电与售电，买方找发电与售电。
     * 真人优先，这样演示时用户能立刻收到邀约；真人收件箱堆满则退回机器人，
     * 不能因为真人谈不动就让整个盘的协商停摆。
     */
    private TradingAccount pickCounterparty(TradingAccount me, Side mySide, List<TradingAccount> peers,
                                            List<Negotiation> mine, Random rnd) {
        List<TradingAccount> humans = new ArrayList<>();
        List<TradingAccount> robots = new ArrayList<>();
        for (TradingAccount a : peers) {
            if (!eligible(me, mySide, a) || overloaded(mine, me.getId(), a.getId())) {
                continue;
            }
            if (a.getSource() == OrderSource.HUMAN) {
                humans.add(a);
            } else {
                robots.add(a);
            }
        }
        // 收件箱是否堆满要读一次对手的协商列表，所以只对真正抽中的候选做检查，最多试三次
        for (int attempt = 0; attempt < 3 && !(humans.isEmpty() && robots.isEmpty()); attempt++) {
            boolean toHuman = !humans.isEmpty() && (robots.isEmpty() || rnd.nextDouble() < PREFER_HUMAN_P);
            List<TradingAccount> pool = toHuman ? humans : robots;
            TradingAccount pick = pool.remove(rnd.nextInt(pool.size()));
            if (!inboxFull(pick.getId())) {
                return pick;
            }
        }
        return null;
    }

    private boolean eligible(TradingAccount me, Side mySide, TradingAccount a) {
        if (a == null || a.getId() == null || a.getId().equals(me.getId())) {
            return false;
        }
        if (a.isFrozen() || !a.isParamsLocked() || a.getMemberType() == null) {
            return false;
        }
        // 我卖电就得找买得起电的（用电/售电），我买电就得找有电可卖的（发电/售电）
        if (mySide == Side.SELL) {
            return a.getMemberType() != MemberType.GENCO;
        }
        return a.getMemberType() != MemberType.CONSUMER;
    }

    // ── 频控 ────────────────────────────────────────────────

    private List<Negotiation> safeList(String accountId) {
        try {
            List<Negotiation> all = negotiationRepo.listByAccount(accountId);
            return all == null ? List.of() : all;
        } catch (Exception e) {
            log.debug("读取协商列表失败 {}: {}", accountId, e.getMessage());
            return List.of();
        }
    }

    private int countInflightInitiated(List<Negotiation> negos, String myAccountId) {
        int n = 0;
        for (Negotiation g : negos) {
            if (g != null && g.getStatus() != null && g.getStatus().isActive()
                    && myAccountId.equals(g.getInitiatorAccountId())) {
                n++;
            }
        }
        return n;
    }

    /** 对手方待回应的邀约是否已经堆满，堆满就换人或这轮不发 */
    private boolean inboxFull(String targetId) {
        int n = 0;
        for (Negotiation g : safeList(targetId)) {
            if (g == null || g.getStatus() == null || !g.getStatus().isActive()) {
                continue;
            }
            // 只数「等着他回应」的，他自己发出去的邀约不算占用收件箱
            if (targetId.equals(g.getCounterpartyAccountId()) && ++n >= MAX_INFLIGHT_TO_TARGET) {
                return true;
            }
        }
        return false;
    }

    /** 与某个对手方之间的在途邀约是否已经到顶（不分谁发起的，避免两台机器人互刷） */
    private boolean overloaded(List<Negotiation> negos, String myAccountId, String otherId) {
        int n = 0;
        for (Negotiation g : negos) {
            if (g == null || g.getStatus() == null || !g.getStatus().isActive()) {
                continue;
            }
            boolean between = (myAccountId.equals(g.getInitiatorAccountId()) && otherId.equals(g.getCounterpartyAccountId()))
                    || (otherId.equals(g.getInitiatorAccountId()) && myAccountId.equals(g.getCounterpartyAccountId()));
            if (between && ++n >= MAX_INFLIGHT_PER_PARTY) {
                return true;
            }
        }
        return false;
    }

    // ── 报价条件 ────────────────────────────────────────────

    /** 邀约电量取单笔上限的一个合理比例，并受自身资金与可售电量约束 */
    private double quantityOf(TradingAccount me, TradingSession sess, Side side, double price,
                              RobotConfig cfg, Random rnd, Limits limits) {
        double base = me.getParams() != null && me.getParams().getMaxSingleOrderMwh() != null
                && me.getParams().getMaxSingleOrderMwh() > 0
                ? me.getParams().getMaxSingleOrderMwh()
                : cfg.getMaxOrderMwh();
        // 双边协商是大宗协议，单笔比订单簿申报大，但不会一次把额度用满
        double want = base * (0.25 + rnd.nextDouble() * 0.35);
        return limits.fixQty(Math.min(want, limits.capacity(side, price)));
    }

    /** 邀约有效期不超过场次闭市时间，否则闭市清理时会立刻被判过期 */
    private int expireMinutes(TradingSession sess, Random rnd) {
        int want = 15 + rnd.nextInt(31);
        long left = sess.getCloseTime() - System.currentTimeMillis();
        if (left > 0) {
            int untilClose = (int) (left / 60_000L);
            want = Math.min(want, Math.max(untilClose, 5));
        }
        return Math.max(want, 5);
    }

    private String message(Robot robot, TradingSession sess, Side side, double price, double qty) {
        String variety = sess.getVariety() == null ? "" : sess.getVariety().getLabel();
        String period = sess.getPeriodType() == null ? "" : sess.getPeriodType().getLabel();
        String scope = variety + period;
        String qtyText = Nums.qty(qty) + " MWh";
        String priceText = Nums.price(price) + " 元/MWh";
        if (side == Side.BUY) {
            return "我方（" + robot.getName() + "）拟采购" + scope + "电量 " + qtyText
                    + "，报价 " + priceText + "，可按月均匀交割，欢迎商洽。";
        }
        return "我方（" + robot.getName() + "）可供" + scope + "电量 " + qtyText
                + "，报价 " + priceText + "，机组出力稳定，欢迎商洽。";
    }
}
