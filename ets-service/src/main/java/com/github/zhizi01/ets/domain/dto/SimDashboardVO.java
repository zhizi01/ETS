package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.enums.SimState;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 管理端监控大屏，对应 GET /api/admin/sims/{id}/dashboard。 */
@Data
public class SimDashboardVO {

    private String simId;
    private String simName;
    private SimState state;
    private String stateLabel;
    private long startedAt;
    private long serverTime;

    // ── 参与规模 ────────────────────────────
    private long accountCount;
    private long humanAccountCount;
    private long robotAccountCount;
    private long userCount;
    private long robotCount;
    private int runningRobotCount;
    private int wsOnlineCount;

    // ── 场次 ────────────────────────────────
    private long sessionCount;
    private long openSessionCount;
    private List<Bucket> sessionStateDist = new ArrayList<>();

    // ── 成交 ────────────────────────────────
    private double totalVolume;
    private double totalAmount;
    private long tradeCount;
    private double avgPrice;

    // ── 分布与排行 ──────────────────────────
    private List<Bucket> memberTypeDist = new ArrayList<>();
    private List<SimRankVO> top10 = new ArrayList<>();

    /** 异常告警：资金见底、亏损过大等，供大屏红字提示 */
    private List<String> alerts = new ArrayList<>();

    @Data
    public static class Bucket {
        private String key;
        private String label;
        private long count;
        /** 该分组的资金或成交额合计，视场景而定 */
        private double amount;

        public static Bucket of(String key, String label, long count, double amount) {
            Bucket b = new Bucket();
            b.key = key;
            b.label = label;
            b.count = count;
            b.amount = amount;
            return b;
        }
    }
}
