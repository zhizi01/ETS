package com.github.zhizi01.ets.domain.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 管理员调整账户资金，对应 POST /api/admin/accounts/{id}/adjust-fund。amount 可正可负。 */
@Data
public class AdminFundAdjustReq {

    @NotNull(message = "调整金额不能为空")
    private Double amount;

    @Size(max = 200, message = "备注过长")
    private String remark;
}
