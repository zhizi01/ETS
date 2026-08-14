package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.enums.TimeInForce;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** POST /api/user/orders */
@Data
public class OrderPlaceReq {

    @NotBlank(message = "不能为空")
    private String accountId;
    @NotBlank(message = "不能为空")
    private String sessionId;
    @NotNull(message = "不能为空")
    private Side side;
    @Positive(message = "必须大于 0")
    private double price;
    @Positive(message = "必须大于 0")
    private double quantity;
    private TimeInForce timeInForce = TimeInForce.GFD;
}
