package com.github.zhizi01.ets.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 盘口快照。 */
@Data
public class Depth {

    private String sessionId;
    private List<Level> bids = new ArrayList<>();
    private List<Level> asks = new ArrayList<>();
    /** 集中申报阶段隐藏价格，只暴露笔数 */
    private boolean hidden;
    private int bidOrderCount;
    private int askOrderCount;
    private double bidTotalQty;
    private double askTotalQty;
    private long ts = System.currentTimeMillis();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Level {
        private double price;
        private double quantity;
        private int orderCount;
    }
}
