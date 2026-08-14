package com.github.zhizi01.ets.domain.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * POST /api/admin/robots/batch
 * strategyMix / memberTypeMix 为「策略或主体 -> 台数」的配比；
 * 都不传时按 count 走内置的默认结构（发电/用电/售电 ≈ 10/9/5）。
 */
@Data
public class RobotBatchReq {

    private String simId;
    private Integer count;
    private Map<String, Integer> strategyMix = new LinkedHashMap<>();
    private Map<String, Integer> memberTypeMix = new LinkedHashMap<>();
    private Double capitalMin;
    private Double capitalMax;
}
