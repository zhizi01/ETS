package com.github.zhizi01.ets.domain.model;

import com.github.zhizi01.ets.domain.enums.SimState;
import lombok.Data;

/**
 * 模拟盘（一场演练）。所有交易数据都挂在某个 simId 下。
 */
@Data
public class Sim {

    private String id;
    private String name;
    private String description;
    private SimState state = SimState.SETUP;

    private String ownerAdminId;
    private long createdAt;
    private long startedAt;
    private long endedAt;

    /** 时钟倍率，1 表示与真实时间 1:1 */
    private double clockSpeed = 1.0;

    /** 仿真交割起始日 yyyy-MM-dd */
    private String baseDate;

    private SimConfig cfg = new SimConfig();
}
