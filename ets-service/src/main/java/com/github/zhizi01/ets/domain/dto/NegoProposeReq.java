package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.enums.Side;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** POST /api/user/negotiations */
@Data
public class NegoProposeReq {

    @NotBlank(message = "不能为空")
    private String accountId;
    @NotBlank(message = "不能为空")
    private String counterpartyAccountId;
    @NotBlank(message = "不能为空")
    private String sessionId;
    /** 发起方的买卖方向 */
    @NotNull(message = "不能为空")
    private Side side;
    @Positive(message = "必须大于 0")
    private double price;
    @Positive(message = "必须大于 0")
    private double quantity;
    private String deliveryStart;
    private String deliveryEnd;
    private String message;
    /** 协商有效期（分钟），缺省 30 */
    private Integer expireMinutes;
}
