package com.github.zhizi01.ets.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 接受 / 拒绝 / 撤回 共用的请求体。 */
@Data
public class NegoActionReq {

    @NotBlank(message = "不能为空")
    private String accountId;
    private String message;
}
