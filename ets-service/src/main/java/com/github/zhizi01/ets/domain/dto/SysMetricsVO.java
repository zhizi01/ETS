package com.github.zhizi01.ets.domain.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** GET /api/admin/sys/metrics */
@Data
public class SysMetricsVO {

    private Jvm jvm = new Jvm();
    private Redis redis = new Redis();
    private Ws ws = new Ws();
    private List<SimMetrics> sims = new ArrayList<>();
    private long ts = System.currentTimeMillis();

    @Data
    public static class Jvm {
        private String javaVersion;
        /** MB */
        private long heapUsedMb;
        private long heapMaxMb;
        private long heapCommittedMb;
        private long nonHeapUsedMb;
        private double heapUsedPct;
        private int threadCount;
        private int peakThreadCount;
        private int daemonThreadCount;
        private int availableProcessors;
        private long uptimeMs;
        private double systemLoadAverage;
    }

    @Data
    public static class Redis {
        private boolean connected;
        private String version;
        private String mode;
        private String usedMemoryHuman;
        private long connectedClients;
        private long uptimeSeconds;
        private long totalCommandsProcessed;
        private long opsPerSec;
        /** 当前 db（db 3）的 key 数量 */
        private long dbSize;
        private String error;
    }

    @Data
    public static class Ws {
        private int onlineCount;
        private int topicCount;
        private int subscriptionCount;
        private long totalConnected;
    }

    /** 单个模拟盘的运行态指标 */
    @Data
    public static class SimMetrics {
        private String simId;
        private String simName;
        private String state;
        private int accountCount;
        private int robotCount;
        private int robotRunning;
        private int sessionCount;
        private int openSessionCount;
        /** 全部场次订单簿里挂着的委托笔数（买 + 卖） */
        private long bookOrders;
        private long bookBids;
        private long bookAsks;
        private double totalVolume;
        private double totalAmount;
        private Map<String, Object> extra = new LinkedHashMap<>();
    }
}
