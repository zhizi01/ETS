package com.github.zhizi01.ets.domain.dto;

import lombok.Data;

/** PUT /api/admin/fundamental?simId= 。字段为 null 表示保持原值。 */
@Data
public class FundamentalUpdateReq {

    private Double coalPriceIdx;
    private Double gasPriceIdx;
    private Double loadFactor;
    private Double windFactor;
    private Double solarFactor;
    private Double outageRatio;
    /** 直接指定基准电价；下一次自然漂移仍会以模拟盘配置为锚重新推算 */
    private Double benchmarkPrice;
}
