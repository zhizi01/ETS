package com.github.zhizi01.ets.domain.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** 管理员重置用户口令。留空则重置为系统默认口令并在响应中回传。 */
@Data
public class AdminPwdResetReq {

    @Size(min = 6, max = 64, message = "新密码长度需在 6-64 之间")
    private String newPassword;
}
