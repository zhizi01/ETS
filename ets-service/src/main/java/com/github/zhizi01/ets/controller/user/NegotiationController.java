package com.github.zhizi01.ets.controller.user;

import com.github.zhizi01.ets.api.AccountPort;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.PageResult;
import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.dto.NegoActionReq;
import com.github.zhizi01.ets.domain.dto.NegoCounterReq;
import com.github.zhizi01.ets.domain.dto.NegoCounterpartyVO;
import com.github.zhizi01.ets.domain.dto.NegoProposeReq;
import com.github.zhizi01.ets.domain.enums.NegoStatus;
import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.model.Negotiation;
import com.github.zhizi01.ets.security.CurrentUser;
import com.github.zhizi01.ets.security.LoginUser;
import com.github.zhizi01.ets.service.trade.NegotiationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 双边协商（docs/API.md 2.6）。 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class NegotiationController {

    private final NegotiationService negotiationService;

    @Lazy
    @Autowired
    private AccountPort accountPort;

    @GetMapping("/counterparties")
    public R<List<NegoCounterpartyVO>> counterparties(@CurrentUser LoginUser me,
                                                      @RequestParam("simId") String simId,
                                                      @RequestParam("accountId") String accountId) {
        accountPort.requireOwned(accountId, me.getId());
        return R.ok(negotiationService.counterparties(simId, accountId));
    }

    @PostMapping("/negotiations")
    public R<Negotiation> propose(@CurrentUser LoginUser me, @Valid @RequestBody NegoProposeReq req) {
        accountPort.requireOwned(req.getAccountId(), me.getId());
        return R.ok(negotiationService.propose(req.getAccountId(), req.getCounterpartyAccountId(),
                req.getSessionId(), req.getSide(), req.getPrice(), req.getQuantity(),
                req.getDeliveryStart(), req.getDeliveryEnd(), req.getMessage(),
                req.getExpireMinutes(), OrderSource.HUMAN, me.getId()));
    }

    @PostMapping("/negotiations/{id}/counter")
    public R<Negotiation> counter(@CurrentUser LoginUser me, @PathVariable("id") String id,
                                  @Valid @RequestBody NegoCounterReq req) {
        accountPort.requireOwned(req.getAccountId(), me.getId());
        return R.ok(negotiationService.counter(id, req.getAccountId(), req.getPrice(),
                req.getQuantity(), req.getMessage()));
    }

    @PostMapping("/negotiations/{id}/accept")
    public R<Negotiation> accept(@CurrentUser LoginUser me, @PathVariable("id") String id,
                                 @Valid @RequestBody NegoActionReq req) {
        accountPort.requireOwned(req.getAccountId(), me.getId());
        return R.ok(negotiationService.accept(id, req.getAccountId()));
    }

    @PostMapping("/negotiations/{id}/reject")
    public R<Negotiation> reject(@CurrentUser LoginUser me, @PathVariable("id") String id,
                                 @Valid @RequestBody NegoActionReq req) {
        accountPort.requireOwned(req.getAccountId(), me.getId());
        return R.ok(negotiationService.reject(id, req.getAccountId(), req.getMessage()));
    }

    @PostMapping("/negotiations/{id}/withdraw")
    public R<Negotiation> withdraw(@CurrentUser LoginUser me, @PathVariable("id") String id,
                                   @Valid @RequestBody NegoActionReq req) {
        accountPort.requireOwned(req.getAccountId(), me.getId());
        return R.ok(negotiationService.withdraw(id, req.getAccountId()));
    }

    @GetMapping("/negotiations")
    public R<PageResult<Negotiation>> list(@CurrentUser LoginUser me,
                                           @RequestParam("accountId") String accountId,
                                           @RequestParam(value = "role", defaultValue = "all") String role,
                                           @RequestParam(value = "status", required = false) String status,
                                           @RequestParam(value = "page", defaultValue = "1") int page,
                                           @RequestParam(value = "size", defaultValue = "20") int size) {
        accountPort.requireOwned(accountId, me.getId());
        List<Negotiation> all = negotiationService.query(accountId, role, parseStatus(status));
        return R.ok(PageResult.slice(all, page, size));
    }

    @GetMapping("/negotiations/{id}")
    public R<Negotiation> detail(@CurrentUser LoginUser me, @PathVariable("id") String id) {
        Negotiation n = negotiationService.detail(id);
        boolean mine = isOwned(n.getInitiatorAccountId(), me.getId())
                || isOwned(n.getCounterpartyAccountId(), me.getId());
        if (!mine) {
            throw BizException.of(ErrorCode.FORBIDDEN, "您不是本次协商的参与方");
        }
        return R.ok(n);
    }

    private boolean isOwned(String accountId, String userId) {
        try {
            accountPort.requireOwned(accountId, userId);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private NegoStatus parseStatus(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return NegoStatus.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "未知的协商状态: " + s);
        }
    }
}
