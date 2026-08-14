package com.github.zhizi01.ets.domain.model;

import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.enums.RobotStrategy;
import lombok.Data;

/** 交易机器人。每个机器人绑定一个 source=ROBOT 的交易账户。 */
@Data
public class Robot {

    private String id;
    private String simId;
    private String accountId;
    private String accountCode;
    private String name;
    private MemberType memberType;
    private RobotStrategy strategy;
    private boolean enabled = true;

    private RobotConfig cfg = new RobotConfig();

    private int orderCount;
    private int tradeCount;
    private int cancelCount;
    private long lastActAt;
    private String lastAction;

    private long createdAt;
    private long updatedAt;
}
