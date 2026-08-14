package com.github.zhizi01.ets.api;

import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.enums.TimeInForce;
import lombok.Builder;
import lombok.Data;

/** 下单指令。真人与机器人共用同一条链路。 */
@Data
@Builder
public class PlaceOrderCmd {

    private String accountId;
    private String sessionId;
    private Side side;
    private double price;
    private double quantity;
    @Builder.Default
    private TimeInForce timeInForce = TimeInForce.GFD;
    @Builder.Default
    private OrderSource source = OrderSource.HUMAN;
    /** 真人下单时校验归属；机器人传 null */
    private String userId;
}
