package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/** 申报来源 */
@Getter
public enum OrderSource implements LabeledEnum {

    HUMAN("真人"),
    ROBOT("机器人");

    private final String label;

    OrderSource(String label) {
        this.label = label;
    }
}
