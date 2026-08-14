package com.github.zhizi01.ets.api;

import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.enums.Side;
import lombok.Builder;
import lombok.Data;

/** 挂牌指令。 */
@Data
@Builder
public class CreateListingCmd {

    private String accountId;
    private String sessionId;
    private Side side;
    private double price;
    private double totalQty;
    private double minTakeQty;
    @Builder.Default
    private boolean allowPartial = true;
    /** 有效截止时间 epochMillis，0 表示跟随场次闭市 */
    private long validUntil;
    private String title;
    private String remark;
    @Builder.Default
    private OrderSource source = OrderSource.HUMAN;
    private String userId;
}
