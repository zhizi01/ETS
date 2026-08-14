package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.enums.SpotBidStatus;
import com.github.zhizi01.ets.domain.model.SpotBid;
import com.github.zhizi01.ets.domain.model.SpotSegment;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** GET /api/user/spot/my-result：我在某场次的 24 点中标结果。 */
@Data
public class SpotResultVO {

    private String bidId;
    private String accountId;
    private String sessionId;
    private Side side;
    private String tradeDate;
    private SpotBidStatus status;
    private String statusLabel;

    private double clearedQty;
    private double clearedAmount;
    private double avgClearedPrice;

    private List<SpotSegment> segments = new ArrayList<>();

    public static SpotResultVO of(SpotBid b) {
        SpotResultVO v = new SpotResultVO();
        if (b == null) {
            return v;
        }
        v.bidId = b.getId();
        v.accountId = b.getAccountId();
        v.sessionId = b.getSessionId();
        v.side = b.getSide();
        v.tradeDate = b.getTradeDate();
        v.status = b.getStatus();
        v.statusLabel = b.getStatus() == null ? null : b.getStatus().getLabel();
        v.clearedQty = Nums.qty(b.getClearedQty());
        v.clearedAmount = Nums.amount(b.getClearedAmount());
        v.avgClearedPrice = Nums.price(Nums.div(b.getClearedAmount(), b.getClearedQty()));
        v.segments = b.getSegments();
        return v;
    }
}
