package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.model.Listing;
import com.github.zhizi01.ets.domain.model.TakeOrder;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** GET /api/user/listings/{id}：挂牌详情 + 摘牌记录。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ListingDetailVO {

    private Listing listing;
    private List<TakeOrder> takes;
    private String statusLabel;

    public static ListingDetailVO of(Listing l, List<TakeOrder> takes) {
        return new ListingDetailVO(l, takes, l.getStatus() == null ? null : l.getStatus().getLabel());
    }
}
