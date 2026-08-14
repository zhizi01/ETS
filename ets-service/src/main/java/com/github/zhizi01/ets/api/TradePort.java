package com.github.zhizi01.ets.api;

import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.model.Listing;
import com.github.zhizi01.ets.domain.model.Negotiation;
import com.github.zhizi01.ets.domain.model.Order;
import com.github.zhizi01.ets.domain.model.SpotBid;
import com.github.zhizi01.ets.domain.model.SpotSegment;
import com.github.zhizi01.ets.domain.model.TakeOrder;

import java.util.List;

/** 交易入口。实现方：撮合交易模块。机器人与真人共用。 */
public interface TradePort {

    /** 下单：风控 -> 冻结 -> 入簿 -> 撮合。被拒时抛 BizException。 */
    Order placeOrder(PlaceOrderCmd cmd);

    /** 撤单。accountId 用于归属校验，机器人可传自己的 accountId。 */
    void cancelOrder(String orderId, String accountId);

    /** 撤销某账户在某场次的全部挂单，返回撤单数 */
    int cancelAll(String accountId, String sessionId);

    Listing createListing(CreateListingCmd cmd);

    void withdrawListing(String listingId, String accountId);

    TakeOrder takeListing(String listingId, String accountId, double quantity, OrderSource source);

    /**
     * 发起双边协商邀约。机器人与真人共用此入口，机器人作为应答方的自动回应由协商服务内部调度。
     */
    Negotiation proposeNegotiation(String accountId, String counterpartyAccountId, String sessionId,
                                   Side side, double price, double quantity,
                                   String message, int expireMinutes, OrderSource source);

    /** 提交日前现货 24 点报价曲线，同账户同场次重复提交视为覆盖 */
    SpotBid submitSpotBid(String accountId, String sessionId, Side side, String tradeDate,
                          List<SpotSegment> segments, OrderSource source);

    /** 集中竞价统一出清 */
    void runCallAuction(String sessionId);

    /** 日前现货 24 点出清 */
    void runSpotClearing(String sessionId);

    /** 场次闭市：撤销全部未成交挂单、释放冻结资金 */
    void closeSession(String sessionId);
}
