package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import lombok.Data;

/** GET /api/user/counterparties */
@Data
public class NegoCounterpartyVO {

    private String accountId;
    private String code;
    private String name;
    private MemberType memberType;
    private String memberTypeLabel;
    private OrderSource source;

    public static NegoCounterpartyVO of(TradingAccount a) {
        NegoCounterpartyVO v = new NegoCounterpartyVO();
        v.accountId = a.getId();
        v.code = a.getCode();
        v.name = a.getName();
        v.memberType = a.getMemberType();
        v.memberTypeLabel = a.getMemberType() == null ? null : a.getMemberType().getLabel();
        v.source = a.getSource();
        return v;
    }
}
