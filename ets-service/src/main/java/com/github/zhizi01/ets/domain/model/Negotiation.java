package com.github.zhizi01.ets.domain.model;

import com.github.zhizi01.ets.domain.enums.NegoStatus;
import com.github.zhizi01.ets.domain.enums.PeriodType;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.enums.Variety;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 双边协商。轮流还价，最多 MAX_ROUNDS 轮。 */
@Data
public class Negotiation {

    public static final int MAX_ROUNDS = 10;

    private String id;
    private String simId;
    private String sessionId;
    private Variety variety;
    private PeriodType periodType = PeriodType.ALL;

    private String initiatorAccountId;
    private String initiatorAccountCode;
    private String initiatorAccountName;
    private String initiatorUserId;

    private String counterpartyAccountId;
    private String counterpartyAccountCode;
    private String counterpartyAccountName;
    private String counterpartyUserId;

    /** 发起方的买卖方向 */
    private Side initiatorSide;

    /** 当前桌面上的报价条件（随每轮还价更新） */
    private double price;
    private double quantity;
    private String deliveryStart;
    private String deliveryEnd;

    private NegoStatus status = NegoStatus.PROPOSED;
    /** 当前轮到谁回应 */
    private String currentTurnAccountId;
    private long expireAt;

    private List<NegoRound> rounds = new ArrayList<>();

    private String tradeId;
    private String contractId;
    private long createdAt;
    private long updatedAt;

    public int roundCount() {
        return rounds == null ? 0 : rounds.size();
    }

    /** 给定账户在本次协商中的买卖方向 */
    public Side sideOf(String accountId) {
        if (accountId == null) {
            return null;
        }
        if (accountId.equals(initiatorAccountId)) {
            return initiatorSide;
        }
        if (accountId.equals(counterpartyAccountId)) {
            return initiatorSide.opposite();
        }
        return null;
    }

    public String buyAccountId() {
        return initiatorSide == Side.BUY ? initiatorAccountId : counterpartyAccountId;
    }

    public String sellAccountId() {
        return initiatorSide == Side.BUY ? counterpartyAccountId : initiatorAccountId;
    }
}
