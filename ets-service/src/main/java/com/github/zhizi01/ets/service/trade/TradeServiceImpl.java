package com.github.zhizi01.ets.service.trade;

import com.github.zhizi01.ets.api.CreateListingCmd;
import com.github.zhizi01.ets.api.PlaceOrderCmd;
import com.github.zhizi01.ets.api.SimPort;
import com.github.zhizi01.ets.api.TradePort;
import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.model.Listing;
import com.github.zhizi01.ets.domain.model.Negotiation;
import com.github.zhizi01.ets.domain.model.Order;
import com.github.zhizi01.ets.domain.model.SpotBid;
import com.github.zhizi01.ets.domain.model.SpotSegment;
import com.github.zhizi01.ets.domain.model.TakeOrder;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.engine.CallAuctionEngine;
import com.github.zhizi01.ets.engine.SpotClearingEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;

/** TradePort 的实现：真人与机器人共用的唯一交易入口，只做编排，语义都在各引擎里。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeServiceImpl implements TradePort {

    private final OrderService orderService;
    private final ListingService listingService;
    private final NegotiationService negotiationService;
    private final SpotBidService spotBidService;
    private final CallAuctionEngine callAuctionEngine;
    private final SpotClearingEngine spotClearingEngine;

    @Lazy
    @Autowired
    private SimPort simPort;

    @Override
    public Order placeOrder(PlaceOrderCmd cmd) {
        return orderService.place(cmd);
    }

    @Override
    public void cancelOrder(String orderId, String accountId) {
        orderService.cancel(orderId, accountId);
    }

    @Override
    public int cancelAll(String accountId, String sessionId) {
        return orderService.cancelAll(accountId, sessionId);
    }

    @Override
    public Listing createListing(CreateListingCmd cmd) {
        return listingService.create(cmd);
    }

    @Override
    public void withdrawListing(String listingId, String accountId) {
        listingService.withdraw(listingId, accountId);
    }

    @Override
    public TakeOrder takeListing(String listingId, String accountId, double quantity, OrderSource source) {
        return listingService.take(listingId, accountId, quantity, source);
    }

    /**
     * 主动发起双边邀约。交割区间不入参，一律取场次自身的交割区间；
     * 归属校验交给 NegotiationService 按 source 判定（机器人账户没有 userId）。
     */
    @Override
    public Negotiation proposeNegotiation(String accountId, String counterpartyAccountId, String sessionId,
                                          Side side, double price, double quantity,
                                          String message, int expireMinutes, OrderSource source) {
        return negotiationService.propose(accountId, counterpartyAccountId, sessionId, side, price, quantity,
                null, null, message, expireMinutes, source, null);
    }

    @Override
    public SpotBid submitSpotBid(String accountId, String sessionId, Side side, String tradeDate,
                                 List<SpotSegment> segments, OrderSource source) {
        return spotBidService.submit(accountId, sessionId, side, tradeDate, segments, source, null);
    }

    @Override
    public void runCallAuction(String sessionId) {
        callAuctionEngine.runCallAuction(sessionId);
    }

    @Override
    public void runSpotClearing(String sessionId) {
        spotClearingEngine.runSpotClearing(sessionId);
    }

    /**
     * 场次闭市清理。三类未了结的头寸都要收干净，否则冻结资金会永远挂在账户上：
     * 订单簿挂单、仍可摘的挂牌、进行中的双边协商。
     */
    @Override
    public void closeSession(String sessionId) {
        TradingSession sess = simPort.requireSession(sessionId);
        int orders = 0;
        int listings = 0;
        int negos = 0;
        if (sess.getTradeMode() != null && sess.getTradeMode().usesOrderBook()) {
            orders = orderService.expireSessionOrders(sessionId);
        }
        try {
            listings = listingService.expireSessionListings(sessionId);
        } catch (Exception e) {
            log.warn("闭市清理挂牌失败 session={}: {}", sessionId, e.getMessage());
        }
        try {
            negos = negotiationService.expireBySession(sessionId);
        } catch (Exception e) {
            log.warn("闭市清理协商失败 session={}: {}", sessionId, e.getMessage());
        }
        log.info("场次闭市清理完成 session={} 撤单={} 撤牌={} 协商过期={}", sessionId, orders, listings, negos);
    }
}
