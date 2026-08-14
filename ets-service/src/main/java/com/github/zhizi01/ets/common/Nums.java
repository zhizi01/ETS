package com.github.zhizi01.ets.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 全系统数值口径：
 * 价格 元/MWh 保留 2 位；电量 MWh 保留 3 位；金额 元 保留 2 位。
 * 模拟器内部一律用 double 计算，仅在落库与展示前收敛精度。
 */
public final class Nums {

    private Nums() {
    }

    public static double price(double v) {
        return round(v, 2);
    }

    public static double qty(double v) {
        return round(v, 3);
    }

    public static double amount(double v) {
        return round(v, 2);
    }

    public static double round(double v, int scale) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0d;
        }
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    /** 将价格对齐到最小变动价位，向最近的 tick 取整。 */
    public static double alignTick(double v, double tickSize) {
        if (tickSize <= 0) {
            return price(v);
        }
        return price(Math.round(v / tickSize) * tickSize);
    }

    /** 把值夹在 [lo, hi] 内。 */
    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static boolean gt(double a, double b) {
        return a - b > 1e-9;
    }

    public static boolean gte(double a, double b) {
        return a - b > -1e-9;
    }

    public static boolean eq(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    public static boolean isPositive(double v) {
        return v > 1e-9;
    }

    /** 安全除法，除数为 0 时返回 0。 */
    public static double div(double a, double b) {
        return Math.abs(b) < 1e-12 ? 0d : a / b;
    }
}
