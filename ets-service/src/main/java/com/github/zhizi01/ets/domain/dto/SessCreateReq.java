package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.enums.PeriodType;
import com.github.zhizi01.ets.domain.enums.TradeMode;
import com.github.zhizi01.ets.domain.enums.Variety;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 创建交易场次，对应 POST /api/admin/sessions。字段口径见 DESIGN.md 5.4。 */
@Data
public class SessCreateReq {

    @NotBlank(message = "模拟盘 id 不能为空")
    private String simId;

    @Size(max = 60, message = "场次名称过长")
    private String name;

    @NotNull(message = "交易品种不能为空")
    private Variety variety;

    private PeriodType periodType;

    @NotNull(message = "交易方式不能为空")
    private TradeMode tradeMode;

    /** 交割区间 yyyy-MM-dd，留空按品种与模拟盘 baseDate 自动推算 */
    private String deliveryStart;
    private String deliveryEnd;

    /** 场次时间轴 epochMillis，留空则由 openAfterMinutes / durationMinutes 推算 */
    private Long preOpenTime;
    private Long openTime;
    private Long closeTime;

    /** 相对当前时间的开市延迟与持续时长，便于管理端快速排期 */
    private Integer openAfterMinutes;
    private Integer durationMinutes;

    private Double priceFloor;
    private Double priceCap;
    private Double minQty;
    private Double maxQty;
    private Double tickSize;
    private Double lotSize;
}
