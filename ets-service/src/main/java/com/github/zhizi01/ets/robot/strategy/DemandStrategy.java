package com.github.zhizi01.ets.robot.strategy;

import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.enums.RobotStrategy;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.enums.TimeInForce;
import com.github.zhizi01.ets.domain.model.AccountParams;
import com.github.zhizi01.ets.domain.model.TradingSession;
import org.springframework.stereotype.Component;

/**
 * 刚性需求：手里有必须完成的采购（或销售）计划，越临近场次收盘、计划完成度越落后，
 * 出价就越激进，最后阶段直接吃对手价。这是收盘前那一波量能的来源。
 */
@Component
public class DemandStrategy implements RobotStrategyHandler {

    /** 一场演练里要完成的量，取年度计划的这个比例 */
    private static final double PLAN_RATIO = 0.2;
    /** 紧迫度超过这个值就转为主动成交 */
    private static final double TAKE_THRESHOLD = 0.3;
    /** 紧迫时最多在锚点上让价这么多 */
    private static final double MAX_CONCESSION = 0.028;

    @Override
    public RobotStrategy strategy() {
        return RobotStrategy.DEMAND;
    }

    @Override
    public void act(Ctx c) {
        double anchor = c.anchor();
        if (anchor <= 0) {
            return;
        }
        Ops ops = c.getOps();
        Side side = naturalSide(c.getMemberType());
        double urgency = urgency(c, side);

        double concession = (0.003 + MAX_CONCESSION * urgency) * (0.6 + 0.8 * c.aggressiveness());
        double price = side == Side.BUY ? anchor * (1 + concession) : anchor * (1 - concession);

        boolean take = urgency > TAKE_THRESHOLD;
        if (take) {
            double opposite = side == Side.BUY ? c.getBestAsk() : c.getBestBid();
            if (opposite > 0) {
                price = opposite;
            }
        }
        price *= 1 + c.getCfg().getPriceBiasPct();

        double qty = scaleQty(c, 0.3 + 0.6 * urgency);
        if (qty <= 0) {
            return;
        }
        // 越急越不能留在簿上等，用 IOC 保证要么马上成交要么立刻重来
        TimeInForce tif = urgency > 0.6 ? TimeInForce.IOC : TimeInForce.GFD;
        if (ops.place(side, ops.fixPrice(price), qty, tif)) {
            ops.mark((side == Side.BUY ? "刚需采购" : "计划售电")
                    + " 紧迫度" + Nums.round(urgency, 2));
        }
    }

    /** 紧迫度 = 时间进度 - 计划完成度，落后越多越急 */
    private double urgency(Ctx c, Side side) {
        double plan = planQty(c);
        double done = 0;
        if (c.getAccount() != null && c.getAccount().getStat() != null) {
            done = side == Side.BUY ? c.getAccount().getStat().getBuyMwh() : c.getAccount().getStat().getSellMwh();
        }
        double progress = plan <= 0 ? 0 : Nums.clamp(done / plan, 0, 1);

        TradingSession s = c.getSession();
        double timeProgress = 0.5;
        if (s.getCloseTime() > s.getOpenTime() && s.getOpenTime() > 0) {
            timeProgress = Nums.clamp(
                    (double) (System.currentTimeMillis() - s.getOpenTime()) / (s.getCloseTime() - s.getOpenTime()),
                    0, 1);
        }
        return Nums.clamp(timeProgress - progress, 0, 1);
    }

    private double planQty(Ctx c) {
        AccountParams p = c.getAccount() == null ? null : c.getAccount().getParams();
        Double base = null;
        if (p != null) {
            if (c.getMemberType() == MemberType.GENCO) {
                base = p.getAnnualEnergyMwh();
            } else if (c.getMemberType() == MemberType.RETAILER) {
                base = p.getAgentDemandMwh() != null ? p.getAgentDemandMwh() : p.getAnnualDemandMwh();
            } else {
                base = p.getAnnualDemandMwh();
            }
        }
        if (base == null || base <= 0) {
            return c.getCfg().getMaxOrderMwh() * 20;
        }
        return base * PLAN_RATIO;
    }
}
