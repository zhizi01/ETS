package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/** 合同状态 */
@Getter
public enum ContractStatus implements LabeledEnum {

    EFFECTIVE("已生效"),
    DELIVERING("交割中"),
    DELIVERED("已交割"),
    TERMINATED("已终止");

    private final String label;

    ContractStatus(String label) {
        this.label = label;
    }
}
