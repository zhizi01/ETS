package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.enums.SimState;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.SimConfig;
import lombok.Data;

/** 模拟盘视图，带参与统计，公共列表与管理端共用。 */
@Data
public class SimVO {

    private String id;
    private String name;
    private String description;
    private SimState state;
    private String stateLabel;

    private String ownerAdminId;
    private long createdAt;
    private long startedAt;
    private long endedAt;

    private double clockSpeed;
    private String baseDate;
    private SimConfig cfg;

    // ── 参与统计 ────────────────────────────
    private long accountCount;
    private long humanAccountCount;
    private long robotAccountCount;
    private long userCount;
    private long sessionCount;
    private long openSessionCount;

    public static SimVO of(Sim s) {
        if (s == null) {
            return null;
        }
        SimVO v = new SimVO();
        v.id = s.getId();
        v.name = s.getName();
        v.description = s.getDescription();
        v.state = s.getState();
        v.stateLabel = s.getState() == null ? null : s.getState().getLabel();
        v.ownerAdminId = s.getOwnerAdminId();
        v.createdAt = s.getCreatedAt();
        v.startedAt = s.getStartedAt();
        v.endedAt = s.getEndedAt();
        v.clockSpeed = s.getClockSpeed();
        v.baseDate = s.getBaseDate();
        v.cfg = s.getCfg();
        return v;
    }
}
