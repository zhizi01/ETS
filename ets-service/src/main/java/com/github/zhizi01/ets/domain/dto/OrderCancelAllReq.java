package com.github.zhizi01.ets.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** POST /api/user/orders/cancel-all */
@Data
public class OrderCancelAllReq {

    @NotBlank(message = "不能为空")
    private String accountId;
    @NotBlank(message = "不能为空")
    private String sessionId;
}
