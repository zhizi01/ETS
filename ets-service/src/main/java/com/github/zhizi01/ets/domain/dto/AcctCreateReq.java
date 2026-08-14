package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.model.AccountParams;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 创建交易账户，对应 POST /api/user/accounts。仅在模拟盘 SETUP 阶段可用。 */
@Data
public class AcctCreateReq {

    @NotBlank(message = "模拟盘 id 不能为空")
    private String simId;

    @NotBlank(message = "账户名称不能为空")
    @Size(max = 40, message = "账户名称过长")
    private String name;

    @NotNull(message = "市场主体类型不能为空")
    private MemberType memberType;

    /** 按 memberType 分支校验必填项，缺失时抛 ACCOUNT_PARAM_MISSING */
    @NotNull(message = "账户参数不能为空")
    private AccountParams params;
}
