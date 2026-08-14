package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.enums.Side;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** POST /api/user/listings */
@Data
public class ListingCreateReq {

    @NotBlank(message = "不能为空")
    private String accountId;
    @NotBlank(message = "不能为空")
    private String sessionId;
    /** SELL 表示挂牌售电，BUY 表示挂牌购电 */
    @NotNull(message = "不能为空")
    private Side side;
    @Positive(message = "必须大于 0")
    private double price;
    @Positive(message = "必须大于 0")
    private double totalQty;
    /** 单次摘牌最小电量，缺省取场次的 lotSize */
    private double minTakeQty;
    private boolean allowPartial = true;
    /** 有效截止时间 epochMillis，0 表示跟随场次闭市 */
    private long validUntil;
    private String title;
    private String remark;
}
