package com.github.zhizi01.ets.controller.user;

import com.github.zhizi01.ets.api.AccountPort;
import com.github.zhizi01.ets.api.CreateListingCmd;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.PageResult;
import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.dto.ListingCreateReq;
import com.github.zhizi01.ets.domain.dto.ListingDetailVO;
import com.github.zhizi01.ets.domain.dto.ListingTakeReq;
import com.github.zhizi01.ets.domain.enums.ListingStatus;
import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.enums.Side;
import com.github.zhizi01.ets.domain.model.Listing;
import com.github.zhizi01.ets.domain.model.TakeOrder;
import com.github.zhizi01.ets.security.CurrentUser;
import com.github.zhizi01.ets.security.LoginUser;
import com.github.zhizi01.ets.service.trade.ListingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 挂牌摘牌（docs/API.md 2.5）。 */
@RestController
@RequestMapping("/api/user/listings")
@RequiredArgsConstructor
public class ListingController {

    private final ListingService listingService;

    @Lazy
    @Autowired
    private AccountPort accountPort;

    @PostMapping
    public R<Listing> create(@CurrentUser LoginUser me, @Valid @RequestBody ListingCreateReq req) {
        accountPort.requireOwned(req.getAccountId(), me.getId());
        return R.ok(listingService.create(CreateListingCmd.builder()
                .accountId(req.getAccountId())
                .sessionId(req.getSessionId())
                .side(req.getSide())
                .price(req.getPrice())
                .totalQty(req.getTotalQty())
                .minTakeQty(req.getMinTakeQty())
                .allowPartial(req.isAllowPartial())
                .validUntil(req.getValidUntil())
                .title(req.getTitle())
                .remark(req.getRemark())
                .source(OrderSource.HUMAN)
                .userId(me.getId())
                .build()));
    }

    @DeleteMapping("/{id}")
    public R<Void> withdraw(@CurrentUser LoginUser me, @PathVariable("id") String id) {
        Listing l = listingService.detail(id);
        accountPort.requireOwned(l.getAccountId(), me.getId());
        listingService.withdraw(id, l.getAccountId());
        return R.ok();
    }

    @GetMapping
    public R<PageResult<Listing>> list(@CurrentUser LoginUser me,
                                       @RequestParam(value = "simId", required = false) String simId,
                                       @RequestParam(value = "sessionId", required = false) String sessionId,
                                       @RequestParam(value = "side", required = false) String side,
                                       @RequestParam(value = "status", required = false) String status,
                                       @RequestParam(value = "mine", defaultValue = "false") boolean mine,
                                       @RequestParam(value = "accountId", required = false) String accountId,
                                       @RequestParam(value = "page", defaultValue = "1") int page,
                                       @RequestParam(value = "size", defaultValue = "20") int size) {
        if (mine) {
            if (accountId == null || accountId.isBlank()) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "查看我的挂牌需要指定 accountId");
            }
            accountPort.requireOwned(accountId, me.getId());
        }
        List<Listing> all = listingService.query(simId, sessionId, parseSide(side), parseStatus(status),
                mine, accountId);
        return R.ok(PageResult.slice(all, page, size));
    }

    @GetMapping("/{id}")
    public R<ListingDetailVO> detail(@PathVariable("id") String id) {
        Listing l = listingService.detail(id);
        return R.ok(ListingDetailVO.of(l, listingService.takes(id)));
    }

    @PostMapping("/{id}/take")
    public R<TakeOrder> take(@CurrentUser LoginUser me, @PathVariable("id") String id,
                             @Valid @RequestBody ListingTakeReq req) {
        accountPort.requireOwned(req.getAccountId(), me.getId());
        return R.ok(listingService.take(id, req.getAccountId(), req.getQuantity(), OrderSource.HUMAN));
    }

    private Side parseSide(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Side.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "未知的买卖方向: " + s);
        }
    }

    private ListingStatus parseStatus(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return ListingStatus.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "未知的挂牌状态: " + s);
        }
    }
}
