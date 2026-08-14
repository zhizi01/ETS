package com.github.zhizi01.ets.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** POST /api/user/listings/{id}/take */
@Data
public class ListingTakeReq {

    @NotBlank(message = "不能为空")
    private String accountId;
    @Positive(message = "必须大于 0")
    private double quantity;
}
