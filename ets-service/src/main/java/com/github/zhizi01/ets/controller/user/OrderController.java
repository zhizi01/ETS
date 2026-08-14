package com.github.zhizi01.ets.controller.user;

import com.github.zhizi01.ets.api.AccountPort;
import com.github.zhizi01.ets.api.PlaceOrderCmd;
import com.github.zhizi01.ets.api.SimPort;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.PageResult;
import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.domain.dto.OrderCancelAllReq;
import com.github.zhizi01.ets.domain.dto.OrderPlaceReq;
import com.github.zhizi01.ets.domain.dto.OrderVO;
import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.enums.OrderStatus;
import com.github.zhizi01.ets.domain.enums.TimeInForce;
import com.github.zhizi01.ets.domain.model.Order;
import com.github.zhizi01.ets.domain.model.Trade;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.security.CurrentUser;
import com.github.zhizi01.ets.security.LoginUser;
import com.github.zhizi01.ets.service.trade.OrderService;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 申报单：滚动撮合 / 集中竞价（docs/API.md 2.4）。 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @Lazy
    @Autowired
    private AccountPort accountPort;
    @Lazy
    @Autowired
    private SimPort simPort;

    @PostMapping("/orders")
    public R<OrderVO> place(@CurrentUser LoginUser me, @Valid @RequestBody OrderPlaceReq req) {
        accountPort.requireOwned(req.getAccountId(), me.getId());
        Order o = orderService.place(PlaceOrderCmd.builder()
                .accountId(req.getAccountId())
                .sessionId(req.getSessionId())
                .side(req.getSide())
                .price(req.getPrice())
                .quantity(req.getQuantity())
                .timeInForce(req.getTimeInForce() == null ? TimeInForce.GFD : req.getTimeInForce())
                .source(OrderSource.HUMAN)
                .userId(me.getId())
                .build());
        return R.ok(OrderVO.of(o, simPort.findSession(o.getSessionId())));
    }

    @DeleteMapping("/orders/{id}")
    public R<Void> cancel(@CurrentUser LoginUser me, @PathVariable("id") String id) {
        Order o = orderService.find(id);
        if (o == null) {
            throw BizException.of(ErrorCode.ORDER_NOT_FOUND);
        }
        accountPort.requireOwned(o.getAccountId(), me.getId());
        orderService.cancel(id, o.getAccountId());
        return R.ok();
    }

    @PostMapping("/orders/cancel-all")
    public R<Integer> cancelAll(@CurrentUser LoginUser me, @Valid @RequestBody OrderCancelAllReq req) {
        accountPort.requireOwned(req.getAccountId(), me.getId());
        return R.ok(orderService.cancelAll(req.getAccountId(), req.getSessionId()));
    }

    @GetMapping("/orders")
    public R<PageResult<OrderVO>> list(@CurrentUser LoginUser me,
                                       @RequestParam("accountId") String accountId,
                                       @RequestParam(value = "sessionId", required = false) String sessionId,
                                       @RequestParam(value = "status", required = false) String status,
                                       @RequestParam(value = "page", defaultValue = "1") int page,
                                       @RequestParam(value = "size", defaultValue = "20") int size) {
        accountPort.requireOwned(accountId, me.getId());
        List<Order> orders = orderService.query(accountId, sessionId, parseStatus(status));
        Map<String, TradingSession> cache = new HashMap<>();
        List<OrderVO> vos = new ArrayList<>(orders.size());
        for (Order o : orders) {
            vos.add(OrderVO.of(o, cache.computeIfAbsent(o.getSessionId(), simPort::findSession)));
        }
        return R.ok(PageResult.slice(vos, page, size));
    }

    @GetMapping("/trades")
    public R<PageResult<Trade>> trades(@CurrentUser LoginUser me,
                                       @RequestParam("accountId") String accountId,
                                       @RequestParam(value = "sessionId", required = false) String sessionId,
                                       @RequestParam(value = "page", defaultValue = "1") int page,
                                       @RequestParam(value = "size", defaultValue = "20") int size) {
        accountPort.requireOwned(accountId, me.getId());
        return R.ok(PageResult.slice(orderService.queryTrades(accountId, sessionId), page, size));
    }

    /** 前端筛选框清空时会传空串，这里统一容错而不是让参数转换直接 400。 */
    private OrderStatus parseStatus(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return OrderStatus.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "未知的申报单状态: " + s);
        }
    }
}
