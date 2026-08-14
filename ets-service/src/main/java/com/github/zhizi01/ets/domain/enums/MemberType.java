package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/** 市场主体类型 */
@Getter
public enum MemberType implements LabeledEnum {

    GENCO("发电企业"),
    CONSUMER("电力用户"),
    RETAILER("售电公司");

    private final String label;

    MemberType(String label) {
        this.label = label;
    }

    /** 账户编号前缀 */
    public String codePrefix() {
        return switch (this) {
            case GENCO -> "G";
            case CONSUMER -> "C";
            case RETAILER -> "R";
        };
    }
}
