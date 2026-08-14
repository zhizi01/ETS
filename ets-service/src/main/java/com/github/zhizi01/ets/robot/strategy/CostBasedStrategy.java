package com.github.zhizi01.ets.robot.strategy;

import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.enums.RobotStrategy;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.model.AccountParams;
import org.springframework.stereotype.Component;

/**
 * 成本定价：发电方以变动成本加成报卖价，用电方以可承受价打折报买价。
 * 成本线是硬约束（低于它宁可不成交），但市场价高于成本线时会贴着市场报，不白白让利。
 */
@Component
public class CostBasedStrategy implements RobotStrategyHandler {

    @Override
    public RobotStrategy strategy() {
        return RobotStrategy.COST_BASED;
    }

    @Override
    public void act(Ctx c) {
        double anchor = c.anchor();
        if (anchor <= 0) {
            return;
        }
        Ops ops = c.getOps();
        AccountParams p = c.getAccount() == null ? null : c.getAccount().getParams();
        double aggr = c.aggressiveness();
        boolean seller = c.getMemberType() == MemberType.GENCO;

        double price;
        double boundary;
        Side side;
        if (seller) {
            side = Side.SELL;
            double cost = variableCost(p, anchor);
            // 加成随激进度递减：越保守要价越高，越激进越愿意薄利多销
            boundary = cost * (1 + 0.04 + 0.18 * (1 - aggr));
            price = Math.max(boundary, anchor * (1 - (0.003 + 0.008 * c.getRnd().nextDouble())));
            if (c.getBestBid() > boundary && c.getRnd().nextDouble() < aggr) {
                // 买一已经高于成本线，直接砸单落袋
                price = c.getBestBid();
            }
        } else {
            side = Side.BUY;
            double cap = acceptCap(p, c.getMemberType(), anchor);
            boundary = cap * (1 - (0.03 + 0.14 * (1 - aggr)));
            price = Math.min(boundary, anchor * (1 + (0.003 + 0.008 * c.getRnd().nextDouble())));
            if (c.getBestAsk() > 0 && c.getBestAsk() < boundary && c.getRnd().nextDouble() < aggr) {
                price = c.getBestAsk();
            }
        }
        price *= 1 + c.getCfg().getPriceBiasPct();

        double fixed = ops.fixPrice(price);
        // 合规化可能把价格推到成本线的错误一侧，这种时候宁可这一 tick 不报
        if (seller ? fixed < boundary * 0.98 : fixed > boundary * 1.02) {
            return;
        }
        double qty = scaleQty(c, 0.5);
        if (qty > 0 && ops.place(side, fixed, qty)) {
            ops.mark((seller ? "成本加成售电 " : "限价采购 ") + Nums.price(fixed));
        }
    }

    private double variableCost(AccountParams p, double anchor) {
        if (p != null && p.getVariableCostPerMwh() != null && p.getVariableCostPerMwh() > 0) {
            return p.getVariableCostPerMwh();
        }
        if (p != null && p.getGenType() != null) {
            return p.getGenType().getTypicalCost();
        }
        return anchor * 0.82;
    }

    private double acceptCap(AccountParams p, MemberType type, double anchor) {
        if (p != null) {
            if (type == MemberType.RETAILER && p.getRetailPricePerMwh() != null && p.getRetailPricePerMwh() > 0) {
                return p.getRetailPricePerMwh();
            }
            if (p.getMaxAcceptPricePerMwh() != null && p.getMaxAcceptPricePerMwh() > 0) {
                return p.getMaxAcceptPricePerMwh();
            }
            if (p.getRetailBenchmarkPrice() != null && p.getRetailBenchmarkPrice() > 0) {
                return p.getRetailBenchmarkPrice();
            }
        }
        return anchor * 1.18;
    }
}
