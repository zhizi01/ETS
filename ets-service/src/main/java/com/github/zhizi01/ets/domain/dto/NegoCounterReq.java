package com.github.zhizi01.ets.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** POST /api/user/negotiations/{id}/counter */
@Data
public class NegoCounterReq {

    @NotBlank(message = "不能为空")
    private String accountId;
    @Positive(message = "必须大于 0")
    private double price;
    /** 不传或传 0 表示沿用桌面上的电量 */
    private double quantity;
    private String message;
}
