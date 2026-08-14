package com.github.zhizi01.ets.robot.strategy;

import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.RobotStrategy;
import com.github.zhizi01.ets.domain.enums.Side;
import org.springframework.stereotype.Component;

/**
 * 噪声交易：在中间价上下随机游走报价。
 * 它不指望赚钱，作用是制造流动性和价格波动——没有噪声单，做市商的报价永远不会被打掉，
 * 盘面就是一潭死水。所以这里有相当比例的单会主动吃对手价，确保真的产生成交。
 */
@Component
public class NoiseStrategy implements RobotStrategyHandler {

    /** 单次游走幅度（相对中间价） */
    private static final double WALK = 0.006;
    /** 按天然方向下单的概率，其余为反向的随机扰动 */
    private static final double NATURAL_BIAS = 0.68;

    @Override
    public RobotStrategy strategy() {
        return RobotStrategy.NOISE;
    }

    @Override
    public void act(Ctx c) {
        double anchor = c.anchor();
        if (anchor <= 0) {
            return;
        }
        Ops ops = c.getOps();
        Side natural = naturalSide(c.getMemberType());
        Side side = c.getRnd().nextDouble() < NATURAL_BIAS ? natural : natural.opposite();
        if (!canQuote(c.getMemberType(), side)) {
            side = natural;
        }

        double drift = Nums.clamp(c.getRnd().nextGaussian() * WALK, -0.025, 0.025);
        double price = anchor * (1 + drift + c.getCfg().getPriceBiasPct());

        // 三成左右的概率直接吃对手价，这是盘面成交量的主要来源
        boolean taker = c.getRnd().nextDouble() < 0.25 + 0.3 * c.aggressiveness();
        if (taker) {
            double opposite = side == Side.BUY ? c.getBestAsk() : c.getBestBid();
            if (opposite > 0) {
                price = opposite;
            } else {
                price = anchor * (side == Side.BUY ? 1.004 : 0.996);
            }
        }

        double qty = scaleQty(c, 0.3);
        if (qty > 0 && ops.place(side, ops.fixPrice(price), qty)) {
            ops.mark((taker ? "主动成交 " : "随机报价 ") + Nums.price(price));
        }
    }
}
