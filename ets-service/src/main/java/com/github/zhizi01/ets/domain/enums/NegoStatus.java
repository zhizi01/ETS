package com.github.zhizi01.ets.domain.enums;

import lombok.Getter;

/** 双边协商状态 */
@Getter
public enum NegoStatus implements LabeledEnum {

    PROPOSED("已发起"),
    COUNTERED("已还价"),
    ACCEPTED("已达成"),
    REJECTED("已拒绝"),
    WITHDRAWN("已撤回"),
    EXPIRED("已过期");

    private final String label;

    NegoStatus(String label) {
        this.label = label;
    }

    /** 仍在进行中，可继续还价/接受/拒绝 */
    public boolean isActive() {
        return this == PROPOSED || this == COUNTERED;
    }
}
