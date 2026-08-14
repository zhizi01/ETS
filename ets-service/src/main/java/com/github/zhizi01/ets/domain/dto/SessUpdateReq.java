package com.github.zhizi01.ets.domain.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改场次。品种/时段/交易方式决定了 instrumentKey 与撮合方式，
 * 已建立订单簿后再改会让行情失真，故这里不开放修改。
 */
@Data
public class SessUpdateReq {

    @Size(max = 60, message = "场次名称过长")
    private String name;

    private String deliveryStart;
    private String deliveryEnd;

    private Long preOpenTime;
    private Long openTime;
    private Long closeTime;

    private Double priceFloor;
    private Double priceCap;
    private Double minQty;
    private Double maxQty;
    private Double tickSize;
    private Double lotSize;
}
