package com.github.zhizi01.ets.robot.strategy;

import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.RobotStrategy;
import com.github.zhizi01.ets.domain.enums.Side;
import org.springframework.stereotype.Component;

/**
 * 做市商：围绕中间价双边挂单赚价差，是盘口深度的主要提供者。
 * 库存偏离目标时把有利一侧的报价收窄、另一侧推远，用成交把库存拉回目标。
 */
@Component
public class MarketMakerStrategy implements RobotStrategyHandler {

    /** 库存偏离的最大影响系数，超过就完全单边 */
    private static final double MAX_SKEW = 0.7;

    @Override
    public RobotStrategy strategy() {
        return RobotStrategy.MARKET_MAKER;
    }

    @Override
    public void act(Ctx c) {
        double anchor = c.anchor();
        if (anchor <= 0) {
            return;
        }
        Ops ops = c.getOps();
        double spread = Math.max(c.getCfg().getSpreadPct(), 0.002);
        double half = anchor * spread / 2;
        double center = anchor * (1 + c.getCfg().getPriceBiasPct());

        double skew = Nums.clamp(
                Nums.div(ops.inventory() - c.getCfg().getInventoryTargetMwh(),
                        Math.max(c.getCfg().getMaxOrderMwh() * 4, 1)),
                -MAX_SKEW, MAX_SKEW);

        // skew > 0 表示手上多头过重：买价压低（少买），卖价收窄（多卖）
        double bid = center - half * (1 + skew);
        double ask = center + half * (1 - skew);

        boolean quoted = false;
        if (canQuote(c.getMemberType(), Side.BUY)) {
            double qty = scaleQty(c, 0.55 * (1 - skew * 0.5));
            if (qty > 0) {
                quoted |= ops.place(Side.BUY, ops.fixPrice(bid), qty);
            }
        }
        if (canQuote(c.getMemberType(), Side.SELL)) {
            double qty = scaleQty(c, 0.55 * (1 + skew * 0.5));
            if (qty > 0) {
                quoted |= ops.place(Side.SELL, ops.fixPrice(ask), qty);
            }
        }
        if (quoted) {
            ops.mark("双边做市 " + Nums.price(bid) + " / " + Nums.price(ask));
        }
    }
}
