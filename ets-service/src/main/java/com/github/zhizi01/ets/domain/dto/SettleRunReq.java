package com.github.zhizi01.ets.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** POST /api/admin/settle/run */
@Data
public class SettleRunReq {

    @NotBlank(message = "不能为空")
    private String simId;
    /** 结算周期，形如 2026-08；终盘结算传 FINAL，缺省即 FINAL */
    private String period;
}
