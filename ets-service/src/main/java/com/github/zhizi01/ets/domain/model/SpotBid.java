package com.github.zhizi01.ets.domain.model;

import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.enums.SpotBidStatus;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 日前现货 24 点报价曲线。 */
@Data
public class SpotBid {

    private String id;
    private String simId;
    private String sessionId;
    private String accountId;
    private String accountCode;
    private String userId;
    private OrderSource source = OrderSource.HUMAN;

    private Side side;
    /** 交割日 yyyy-MM-dd */
    private String tradeDate;

    /** 必须覆盖 0-23 共 24 段 */
    private List<SpotSegment> segments = new ArrayList<>();

    private SpotBidStatus status = SpotBidStatus.ACTIVE;

    /** 出清汇总 */
    private double clearedQty;
    private double clearedAmount;

    private long createdAt;
    private long updatedAt;
}
