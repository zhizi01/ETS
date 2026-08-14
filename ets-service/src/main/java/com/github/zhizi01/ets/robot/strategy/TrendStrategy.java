package com.github.zhizi01.ets.robot.strategy;

import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.RobotStrategy;
import com.github.zhizi01.ets.domain.enums.Side;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 趋势跟随：读近 20 根 1 分钟 K 线算动量，顺势追价，动量越强越激进。
 * 动量方向与自身主体的天然方向冲突时（比如用电户遇到下跌趋势），
 * 退化为在低位挂被动买单——刚需仍要采购，只是等更好的价。
 */
@Component
public class TrendStrategy implements RobotStrategyHandler {

    private static final int LOOKBACK = 20;
    /** 动量放大倍数：电力市场日内波动小，需要放大才能形成信号 */
    private static final double GAIN = 12;
    /** 超过这个强度就直接吃对手价 */
    private static final double CHASE_THRESHOLD = 0.45;

    @Override
    public RobotStrategy strategy() {
        return RobotStrategy.TREND;
    }

    @Override
    public void act(Ctx c) {
        double anchor = c.anchor();
        if (anchor <= 0) {
            return;
        }
        Ops ops = c.getOps();
        double strength = momentum(ops.closes(LOOKBACK));
        if (Math.abs(strength) < 0.03) {
            // 无趋势时给一个很小的随机方向，保持盘面不至于完全静止
            strength = (c.getRnd().nextDouble() - 0.5) * 0.12;
        }

        Side desired = strength > 0 ? Side.BUY : Side.SELL;
        boolean against = !canQuote(c.getMemberType(), desired);
        Side side = against ? naturalSide(c.getMemberType()) : desired;

        double aggr = c.aggressiveness() * (0.35 + Math.abs(strength));
        double price;
        if (against) {
            // 逆势方向：远离盘口挂单，等价格自己过来
            price = side == Side.BUY ? anchor * (1 - 0.012 * aggr) : anchor * (1 + 0.012 * aggr);
        } else if (Math.abs(strength) > CHASE_THRESHOLD) {
            double opposite = side == Side.BUY ? c.getBestAsk() : c.getBestBid();
            price = opposite > 0 ? opposite
                    : anchor * (side == Side.BUY ? 1 + 0.010 * aggr : 1 - 0.010 * aggr);
        } else {
            price = side == Side.BUY ? anchor * (1 + 0.004 * aggr) : anchor * (1 - 0.004 * aggr);
        }
        price *= 1 + c.getCfg().getPriceBiasPct();

        double qty = scaleQty(c, 0.35 + 0.45 * Math.abs(strength));
        if (qty <= 0) {
            return;
        }
        if (ops.place(side, ops.fixPrice(price), qty)) {
            ops.mark((strength > 0 ? "顺势做多" : "顺势做空") + " 动量" + Nums.round(strength, 3));
        }
    }

    /** 以最近收盘价相对前段均值的偏离度衡量动量，结果压到 [-1, 1] */
    private double momentum(List<Double> closes) {
        if (closes == null || closes.size() < 4) {
            return 0;
        }
        int head = Math.max(closes.size() / 3, 1);
        double sum = 0;
        for (int i = 0; i < head; i++) {
            sum += closes.get(i);
        }
        double base = sum / head;
        if (base <= 0) {
            return 0;
        }
        double last = closes.get(closes.size() - 1);
        return Nums.clamp((last - base) / base * GAIN, -1, 1);
    }
}
