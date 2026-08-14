package com.github.zhizi01.ets.robot;

import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.GenType;
import com.github.zhizi01.ets.domain.enums.LoadProfile;
import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.enums.PeriodType;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.model.AccountParams;
import com.github.zhizi01.ets.domain.model.RobotConfig;
import com.github.zhizi01.ets.domain.model.SpotSegment;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.domain.model.TradingSession;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.DoubleUnaryOperator;

/**
 * 机器人的日前现货 24 点报价曲线构造器。
 * <p>
 * 电量口径统一取「年度电量 / 8760 = 平均出力(MW)」再乘小时形状系数，
 * 这样发电侧与用电侧的总量天然可比（本默认阵容下日需求约为可用供给的三分之二），
 * 出清点才会落在供给曲线的中段，而不是被压在最便宜或最贵的一端。
 * <p>
 * 价格口径分三类主体：发电按变动成本加成、用电按可承受价打折、售电按零售价倒推采购上限。
 */
@Component
public class SpotCurveBuilder {

    private static final int HOURS = 24;

    /**
     * 分时段划分（尖峰/峰/平/谷），对应国内典型的分时电价时段表。
     * 报价的峰谷形态靠它塑形，出清后的 24 点价格曲线才有起伏。
     */
    private static final PeriodType[] TOU = {
            PeriodType.VALLEY, PeriodType.VALLEY, PeriodType.VALLEY, PeriodType.VALLEY,
            PeriodType.VALLEY, PeriodType.VALLEY, PeriodType.VALLEY, PeriodType.FLAT,
            PeriodType.FLAT, PeriodType.PEAK, PeriodType.PEAK, PeriodType.PEAK,
            PeriodType.FLAT, PeriodType.FLAT, PeriodType.PEAK, PeriodType.PEAK,
            PeriodType.PEAK, PeriodType.PEAK, PeriodType.SHARP, PeriodType.SHARP,
            PeriodType.PEAK, PeriodType.PEAK, PeriodType.FLAT, PeriodType.VALLEY};

    /**
     * PeriodType.priceFactor 是相对全时段基准的比价（谷 0.62 ~ 尖峰 1.65），
     * 但现货场次同时受「前收 ± priceLimitPct」的日内涨跌幅约束（本盘为 ±15%），
     * 原样套用会让绝大多数小时段被夹到涨跌停上，24 点曲线反而被削成方波。
     * 所以这里把比价向 1 压缩，保留峰谷次序的同时让曲线整体落在可报价区间内。
     */
    private static final double TOU_COMPRESSION = 0.25;

    /** 典型系统负荷曲线，缺少负荷特性时兜底 */
    private static final double[] SYSTEM_SHAPE = {
            0.78, 0.72, 0.68, 0.66, 0.68, 0.75, 0.88, 1.02, 1.15, 1.22, 1.25, 1.18,
            1.05, 1.12, 1.20, 1.24, 1.22, 1.15, 1.28, 1.32, 1.20, 1.05, 0.92, 0.83};

    /** 光伏出力：日出前与日落后为 0，正午见顶 */
    private static final double[] SOLAR_SHAPE = {
            0.00, 0.00, 0.00, 0.00, 0.00, 0.02, 0.10, 0.30, 0.55, 0.78, 0.93, 1.00,
            0.98, 0.92, 0.80, 0.62, 0.40, 0.18, 0.04, 0.00, 0.00, 0.00, 0.00, 0.00};

    /** 风电出力：夜间与凌晨偏大，午后偏小，与光伏刚好互补 */
    private static final double[] WIND_SHAPE = {
            1.28, 1.32, 1.34, 1.32, 1.26, 1.16, 1.02, 0.88, 0.78, 0.70, 0.66, 0.64,
            0.64, 0.68, 0.74, 0.80, 0.88, 0.98, 1.08, 1.16, 1.22, 1.26, 1.28, 1.30};

    /** 常规机组的可用出力：夜间安排检修与压出力，白天顶峰能力略高 */
    private static final double[] THERMAL_SHAPE = {
            0.86, 0.82, 0.80, 0.80, 0.82, 0.88, 0.96, 1.04, 1.10, 1.13, 1.14, 1.10,
            1.04, 1.08, 1.13, 1.14, 1.12, 1.08, 1.14, 1.15, 1.08, 1.00, 0.94, 0.90};

    /** 一年 8760 小时，用于把年度电量摊成平均出力 */
    private static final double HOURS_PER_YEAR = 8760;

    /**
     * 构造某个账户在该现货场次上的 24 点报价曲线。
     *
     * @param priceFix 价格合规化函数（夹限价与涨跌停并对齐最小变动价位），由调用方提供
     */
    public List<SpotSegment> build(TradingAccount acct, MemberType mt, Side side, TradingSession sess,
                                  RobotConfig cfg, double ref, Random rnd, DoubleUnaryOperator priceFix) {
        AccountParams p = acct.getParams();
        double[] qtyShape = quantityShape(mt, p);
        double baseMw = baseMw(mt, p, cfg);
        // 同一台机器人整条曲线共用一个报价性格，避免相邻小时价格互相打架
        double bias = 1 + (rnd.nextDouble() - 0.5) * 0.03;
        double lot = sess.getLotSize() > 0 ? sess.getLotSize() : 1;
        double minQty = Math.max(sess.getMinQty(), lot);
        double maxQty = sess.getMaxQty() > 0 ? sess.getMaxQty() : Double.MAX_VALUE;
        double tick = sess.getTickSize() > 0 ? sess.getTickSize() : 0.01;

        List<SpotSegment> out = new ArrayList<>(HOURS);
        for (int h = 0; h < HOURS; h++) {
            double shape = qtyShape[h];
            double qty = quantityOf(baseMw * shape, shape, lot, minQty, maxQty, rnd);
            double price = priceOf(mt, p, side, ref, cfg, h, bias, rnd, priceFix, tick);
            // 出力为 0 的小时（例如光伏夜间）报 0 电量，出清引擎会跳过该段
            out.add(new SpotSegment(h, qty > 0 ? price : priceFix.applyAsDouble(ref), qty));
        }
        return out;
    }

    // ── 电量 ────────────────────────────────────────────────

    /** 平均出力 MW：发电看年度可发电量，用电看年度需求，售电看代理电量 */
    private double baseMw(MemberType mt, AccountParams p, RobotConfig cfg) {
        Double annual = null;
        if (p != null) {
            annual = switch (mt) {
                case GENCO -> p.getAnnualEnergyMwh();
                case CONSUMER -> p.getAnnualDemandMwh();
                case RETAILER -> p.getAgentDemandMwh();
            };
        }
        if (annual != null && annual > 0) {
            double mw = annual / HOURS_PER_YEAR;
            // 装机是任一小时的物理上限，年度电量摊出来的均值不应超过它
            if (mt == MemberType.GENCO && p.getInstalledCapacityMw() != null && p.getInstalledCapacityMw() > 0) {
                mw = Math.min(mw, p.getInstalledCapacityMw());
            }
            return mw;
        }
        // 参数缺失时退回按单笔上限估一个规模，保证仍然报得出曲线
        return Math.max(cfg.getMaxOrderMwh() / 24, 10);
    }

    private double[] quantityShape(MemberType mt, AccountParams p) {
        if (mt == MemberType.GENCO) {
            GenType g = p == null ? null : p.getGenType();
            if (g == GenType.SOLAR) {
                return SOLAR_SHAPE;
            }
            if (g == GenType.WIND) {
                return WIND_SHAPE;
            }
            return THERMAL_SHAPE;
        }
        if (mt == MemberType.CONSUMER && p != null && p.getLoadProfile() != null) {
            return p.getLoadProfile().hourlyShape();
        }
        if (mt == MemberType.RETAILER) {
            return retailShape();
        }
        return SYSTEM_SHAPE;
    }

    /** 售电公司代理的是居民与商业混合用户，曲线取两者的平均 */
    private double[] retailShape() {
        double[] a = LoadProfile.RESIDENTIAL.hourlyShape();
        double[] b = LoadProfile.COMMERCIAL.hourlyShape();
        double[] out = new double[HOURS];
        for (int h = 0; h < HOURS; h++) {
            out[h] = (a[h] + b[h]) / 2;
        }
        return out;
    }

    private double quantityOf(double raw, double shape, double lot, double minQty, double maxQty, Random rnd) {
        if (shape <= 1e-6 || raw <= 0) {
            return 0;
        }
        double q = raw * (0.94 + rnd.nextDouble() * 0.12);
        q = Math.min(q, maxQty);
        q = Math.floor(q / lot) * lot;
        if (q < minQty) {
            // 出力形状说明这个小时确实有电量，凑到最小申报量，否则整段会被出清引擎忽略
            q = Math.ceil(minQty / lot) * lot;
        }
        return Nums.qty(Math.min(q, maxQty));
    }

    // ── 价格 ────────────────────────────────────────────────

    /** 分时比价系数，已按 TOU_COMPRESSION 压缩到可报价区间内 */
    private double hourFactor(int hour) {
        return 1 + (TOU[hour].getPriceFactor() - 1) * TOU_COMPRESSION;
    }

    private double priceOf(MemberType mt, AccountParams p, Side side, double ref, RobotConfig cfg,
                           int hour, double bias, Random rnd, DoubleUnaryOperator priceFix, double tick) {
        double aggr = Nums.clamp(cfg.getAggressiveness() <= 0 ? 0.5 : cfg.getAggressiveness(), 0.05, 1.0);
        double factor = hourFactor(hour);
        double jitter = 1 + (rnd.nextDouble() - 0.5) * 0.012;

        if (side == Side.SELL) {
            GenType g = p == null ? null : p.getGenType();
            if (g != null && g.isRenewable()) {
                // 新能源边际成本近零且必须消纳，报可报区间的最低价抢发电权。
                // 现货场次同样受涨跌幅约束，所以这里报出来的"近零价"实际会被夹到跌停，
                // 但相对次序不变：新能源永远排在供给曲线最左端，优先出清。
                double low = priceFix.applyAsDouble(ref * 0.05);
                return priceFix.applyAsDouble(low + tick * rnd.nextInt(3));
            }
            double cost = p != null && p.getVariableCostPerMwh() != null && p.getVariableCostPerMwh() > 0
                    ? p.getVariableCostPerMwh()
                    : (g != null ? g.getTypicalCost() : ref * 0.85);
            // 加成随激进度下降：越激进的机组越愿意薄利多销，抢在供给曲线更左侧
            double markup = 0.04 + 0.14 * (1 - aggr);
            return priceFix.applyAsDouble(cost * (1 + markup) * factor * bias * jitter);
        }

        double willing = willingness(mt, p, ref);
        // 折扣随激进度下降：保守的买方压价更狠，成交概率也更低
        double discount = 0.02 + 0.10 * (1 - aggr);
        double own = willing * (1 - discount) * factor;
        // 完全按自身可承受价报会让参数偏离市场的账户长期出清不了，
        // 掺入四分之一的市场参考价把曲线拉回盘面附近
        double blended = own * 0.75 + ref * factor * 0.25;
        // 峰段愿意出高价，但任何小时都不会超过自己的可承受上限
        return priceFix.applyAsDouble(Math.min(blended, willing) * bias * jitter);
    }

    /** 买方的价格天花板：用电看可承受最高购电价，售电看零售套餐价倒推 */
    private double willingness(MemberType mt, AccountParams p, double ref) {
        if (p != null) {
            if (mt == MemberType.RETAILER && p.getRetailPricePerMwh() != null && p.getRetailPricePerMwh() > 0) {
                return p.getRetailPricePerMwh();
            }
            if (p.getMaxAcceptPricePerMwh() != null && p.getMaxAcceptPricePerMwh() > 0) {
                return p.getMaxAcceptPricePerMwh();
            }
        }
        return ref * 1.15;
    }
}
