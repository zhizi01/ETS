package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/** 模拟盘状态 */
@Getter
public enum SimState implements LabeledEnum {

    SETUP("筹备中"),
    RUNNING("交易中"),
    PAUSED("已暂停"),
    ENDED("已结束");

    private final String label;

    SimState(String label) {
        this.label = label;
    }

    /** 该阶段允许用户创建/修改/删除交易账户 */
    public boolean allowsAccountEdit() {
        return this == SETUP;
    }

    /** 该阶段允许申报交易 */
    public boolean allowsTrading() {
        return this == RUNNING;
    }
}
