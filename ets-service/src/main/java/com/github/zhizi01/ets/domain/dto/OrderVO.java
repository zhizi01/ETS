package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.enums.OrderStatus;
import com.github.zhizi01.ets.domain.enums.PeriodType;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.enums.TimeInForce;
import com.github.zhizi01.ets.domain.enums.TradeMode;
import com.github.zhizi01.ets.domain.enums.Variety;
import com.github.zhizi01.ets.domain.model.Order;
import com.github.zhizi01.ets.domain.model.TradingSession;
import lombok.Data;

/** 委托列表用的展示对象，把前端要显示的场次名与中文标签一次带齐。 */
@Data
public class OrderVO {

    private String id;
    private String simId;
    private String sessionId;
    private String sessionName;
    private String accountId;
    private OrderSource source;

    private Variety variety;
    private PeriodType periodType;
    private String instrumentKey;
    private TradeMode tradeMode;
    private Side side;
    private String sideLabel;
    private TimeInForce timeInForce;

    private double price;
    private double quantity;
    private double filledQty;
    private double remainQty;
    private double avgFillPrice;
    private double frozenAmount;

    private OrderStatus status;
    private String statusLabel;
    private String rejectReason;
    private long seq;

    private long createdAt;
    private long updatedAt;
    private long canceledAt;

    public static OrderVO of(Order o, TradingSession sess) {
        OrderVO v = new OrderVO();
        v.id = o.getId();
        v.simId = o.getSimId();
        v.sessionId = o.getSessionId();
        v.sessionName = sess == null ? null : sess.getName();
        v.accountId = o.getAccountId();
        v.source = o.getSource();
        v.variety = o.getVariety();
        v.periodType = o.getPeriodType();
        v.instrumentKey = o.getVariety() == null ? null : o.instrumentKey();
        v.tradeMode = o.getTradeMode();
        v.side = o.getSide();
        v.sideLabel = o.getSide() == null ? null : o.getSide().getLabel();
        v.timeInForce = o.getTimeInForce();
        v.price = Nums.price(o.getPrice());
        v.quantity = Nums.qty(o.getQuantity());
        v.filledQty = Nums.qty(o.getFilledQty());
        v.remainQty = Nums.qty(o.remainQty());
        v.avgFillPrice = Nums.price(o.getAvgFillPrice());
        v.frozenAmount = Nums.amount(o.getFrozenAmount());
        v.status = o.getStatus();
        v.statusLabel = o.getStatus() == null ? null : o.getStatus().getLabel();
        v.rejectReason = o.getRejectReason();
        v.seq = o.getSeq();
        v.createdAt = o.getCreatedAt();
        v.updatedAt = o.getUpdatedAt();
        v.canceledAt = o.getCanceledAt();
        return v;
    }
}
