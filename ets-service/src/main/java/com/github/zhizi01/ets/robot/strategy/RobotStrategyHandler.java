package com.github.zhizi01.ets.robot.strategy;

import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.enums.RobotStrategy;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.enums.TimeInForce;
import com.github.zhizi01.ets.domain.enums.Variety;
import com.github.zhizi01.ets.domain.model.Fundamental;
import com.github.zhizi01.ets.domain.model.Robot;
import com.github.zhizi01.ets.domain.model.RobotConfig;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.domain.model.TradingSession;
import lombok.Data;

import java.util.List;
import java.util.Random;

/**
 * 一种机器人策略。每个 tick 由 RobotRunner 组装好 {@link Ctx} 后调用 {@link #act}。
 * <p>
 * 策略只负责"报什么价、报多少、报哪边"，下单合规化、风控异常吞掉、统计回写
 * 全部由 {@link Ops} 承担，策略实现里不需要写任何 try/catch。
 */
public interface RobotStrategyHandler {

    RobotStrategy strategy();

    void act(Ctx ctx);

    /** 主体的天然交易方向：发电卖电，用电与售电买电 */
    default Side naturalSide(MemberType type) {
        return type == MemberType.GENCO ? Side.SELL : Side.BUY;
    }

    /**
     * 该主体是否被允许报这个方向。
     * 用电企业没有电量可卖，硬报卖单只会被风控拒绝，白白浪费 tick。
     */
    default boolean canQuote(MemberType type, Side side) {
        if (side == Side.SELL) {
            return type != MemberType.CONSUMER;
        }
        return true;
    }

    /** 单笔申报量：以 maxOrderMwh 为上限做随机缩放，避免每笔都一样大 */
    default double scaleQty(Ctx ctx, double ratio) {
        double base = ctx.getCfg().getMaxOrderMwh() * ratio;
        return ctx.getOps().fixQty(base * (0.55 + 0.75 * ctx.getRnd().nextDouble()));
    }

    /** 一次 tick 的全部上下文 */
    @Data
    class Ctx {
        private Robot robot;
        private RobotConfig cfg;
        private TradingAccount account;
        private MemberType memberType;
        private Sim sim;
        private TradingSession session;
        private Fundamental fundamental;

        /** 该场次标的的参考价，恒大于 0 */
        private double reference;
        /** 盘口中间价，无盘口时等于参考价 */
        private double mid;
        private double bestBid;
        private double bestAsk;

        private Random rnd;
        private Ops ops;

        /** 报价锚点 */
        public double anchor() {
            if (mid > 0) {
                return mid;
            }
            return reference;
        }

        public double aggressiveness() {
            double a = cfg == null ? 0.5 : cfg.getAggressiveness();
            return a <= 0 ? 0.1 : Math.min(a, 1.0);
        }
    }

    /** 策略可以执行的动作。全部方法都不会抛业务异常，失败返回 false / null。 */
    interface Ops {

        /** 申报下单，被风控拒绝时返回 false */
        boolean place(Side side, double price, double quantity, TimeInForce tif);

        default boolean place(Side side, double price, double quantity) {
            return place(side, price, quantity, TimeInForce.GFD);
        }

        /** 价格合规化：夹进申报区间与涨跌停，再对齐最小变动价位 */
        double fixPrice(double raw);

        /** 电量合规化：夹进场次与账户限额并对齐最小单位，不满足最小申报量返回 0 */
        double fixQty(double raw);

        /** 最近 n 根 1 分钟收盘价，时间升序 */
        List<Double> closes(int n);

        /** 同一时段下其它品种的参考价，供跨品种套利比价 */
        double refOf(Variety variety);

        /** 净持仓 MWh（累计买入 - 累计卖出） */
        double inventory();

        /** 记录一次行为，展示在管理端机器人列表的"最近动作"上 */
        void mark(String action);
    }
}
