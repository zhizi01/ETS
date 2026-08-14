package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/** 用电负荷特性，决定 24 小时用电曲线形状 */
@Getter
public enum LoadProfile implements LabeledEnum {

    CONTINUOUS("连续生产"),
    TWO_SHIFT("两班制"),
    THREE_SHIFT("三班制"),
    COMMERCIAL("商业"),
    RESIDENTIAL("居民");

    private final String label;

    LoadProfile(String label) {
        this.label = label;
    }

    /**
     * 归一化的 24 小时负荷系数（均值约 1.0），用于现货曲线与偏差考核。
     */
    public double[] hourlyShape() {
        return switch (this) {
            case CONTINUOUS -> new double[]{
                    1.00, 1.00, 0.99, 0.99, 0.99, 1.00, 1.00, 1.01, 1.01, 1.01, 1.01, 1.00,
                    1.00, 1.00, 1.01, 1.01, 1.01, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00, 1.00};
            case TWO_SHIFT -> new double[]{
                    0.45, 0.42, 0.40, 0.40, 0.45, 0.60, 0.90, 1.30, 1.45, 1.50, 1.50, 1.35,
                    1.15, 1.35, 1.50, 1.50, 1.45, 1.30, 1.05, 0.80, 0.65, 0.58, 0.52, 0.48};
            case THREE_SHIFT -> new double[]{
                    0.85, 0.83, 0.82, 0.82, 0.85, 0.92, 1.05, 1.18, 1.22, 1.22, 1.20, 1.10,
                    1.02, 1.12, 1.22, 1.22, 1.18, 1.10, 1.00, 0.95, 0.92, 0.90, 0.88, 0.86};
            case COMMERCIAL -> new double[]{
                    0.35, 0.32, 0.30, 0.30, 0.32, 0.40, 0.60, 0.95, 1.35, 1.55, 1.60, 1.55,
                    1.40, 1.50, 1.60, 1.58, 1.50, 1.35, 1.20, 1.05, 0.85, 0.65, 0.50, 0.40};
            case RESIDENTIAL -> new double[]{
                    0.55, 0.48, 0.44, 0.42, 0.42, 0.48, 0.68, 0.88, 0.92, 0.88, 0.85, 0.90,
                    0.95, 0.90, 0.88, 0.92, 1.05, 1.35, 1.75, 1.90, 1.80, 1.50, 1.05, 0.75};
        };
    }
}
