package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.model.SpotSegment;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/** POST /api/user/spot/bids，segments 必须覆盖 0~23 共 24 段。 */
@Data
public class SpotBidReq {

    @NotBlank(message = "不能为空")
    private String accountId;
    @NotBlank(message = "不能为空")
    private String sessionId;
    @NotNull(message = "不能为空")
    private Side side;
    private String tradeDate;
    @NotEmpty(message = "不能为空")
    private List<SpotSegment> segments;
}
