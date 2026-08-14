package com.github.zhizi01.ets.robot.strategy;

import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.RobotStrategy;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.enums.Variety;
import org.springframework.stereotype.Component;

/**
 * 跨品种套利：比较年度 / 月度 / 日前现货在同一时段上的参考价，
 * 本品种明显偏贵就在这里卖、偏宜就在这里买，把三个品种的价格拉回同一水平。
 * 没有价差信号时退化为在远离盘口处挂被动单，仍然贡献深度。
 */
@Component
public class ArbitrageStrategy implements RobotStrategyHandler {

    private static final Variety[] WATCH = {Variety.ANNUAL, Variety.MONTHLY, Variety.SPOT_DA};

    @Override
    public RobotStrategy strategy() {
        return RobotStrategy.ARBITRAGE;
    }

    @Override
    public void act(Ctx c) {
        double anchor = c.anchor();
        if (anchor <= 0 || c.getSession().getVariety() == null) {
            return;
        }
        Ops ops = c.getOps();
        Variety mine = c.getSession().getVariety();

        double sum = 0;
        int n = 0;
        for (Variety v : WATCH) {
            if (v == mine) {
                continue;
            }
            double r = ops.refOf(v);
            if (r > 0) {
                sum += r;
                n++;
            }
        }
        double peer = n == 0 ? 0 : sum / n;
        double spread = peer > 0 ? (c.getReference() - peer) / peer : 0;
        double threshold = 0.012 + 0.02 * (1 - c.aggressiveness());

        Side side;
        double price;
        String action;
        if (spread > threshold && canQuote(c.getMemberType(), Side.SELL)) {
            // 本品种偏贵：卖出，价格贴着买一以求尽快成交
            side = Side.SELL;
            price = c.getBestBid() > 0 ? c.getBestBid() : anchor * (1 - 0.004);
            action = "价差卖出 " + Nums.round(spread * 100, 2) + "%";
        } else if (spread < -threshold) {
            side = Side.BUY;
            price = c.getBestAsk() > 0 ? c.getBestAsk() : anchor * (1 + 0.004);
            action = "价差买入 " + Nums.round(spread * 100, 2) + "%";
        } else {
            side = naturalSide(c.getMemberType());
            double away = 0.008 + 0.006 * c.getRnd().nextDouble();
            price = side == Side.BUY ? anchor * (1 - away) : anchor * (1 + away);
            action = "等待价差";
        }
        price *= 1 + c.getCfg().getPriceBiasPct();

        double qty = scaleQty(c, Math.abs(spread) > threshold ? 0.6 : 0.3);
        if (qty > 0 && ops.place(side, ops.fixPrice(price), qty)) {
            ops.mark(action);
        }
    }
}
