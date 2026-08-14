package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.model.AccountParams;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改交易账户。memberType 一经确定不可改（改类型等同于换一个市场主体，
 * 应当删除后重建），因此这里只允许改名称与参数。
 */
@Data
public class AcctUpdateReq {

    @Size(max = 40, message = "账户名称过长")
    private String name;

    private AccountParams params;
}
