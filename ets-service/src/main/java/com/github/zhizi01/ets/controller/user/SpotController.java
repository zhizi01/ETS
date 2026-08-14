package com.github.zhizi01.ets.controller.user;

import com.github.zhizi01.ets.api.AccountPort;
import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.dto.SpotBidReq;
import com.github.zhizi01.ets.domain.dto.SpotResultVO;
import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.model.SpotBid;
import com.github.zhizi01.ets.security.CurrentUser;
import com.github.zhizi01.ets.security.LoginUser;
import com.github.zhizi01.ets.service.trade.SpotBidService;
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
import java.util.Map;

/** 日前现货（docs/API.md 2.7）。 */
@RestController
@RequestMapping("/api/user/spot")
@RequiredArgsConstructor
public class SpotController {

    private final SpotBidService spotBidService;

    @Lazy
    @Autowired
    private AccountPort accountPort;

    @PostMapping("/bids")
    public R<SpotBid> submit(@CurrentUser LoginUser me, @Valid @RequestBody SpotBidReq req) {
        accountPort.requireOwned(req.getAccountId(), me.getId());
        return R.ok(spotBidService.submit(req.getAccountId(), req.getSessionId(), req.getSide(),
                req.getTradeDate(), req.getSegments(), OrderSource.HUMAN, me.getId()));
    }

    @DeleteMapping("/bids/{id}")
    public R<Void> cancel(@CurrentUser LoginUser me, @PathVariable("id") String id) {
        SpotBid bid = spotBidService.require(id);
        accountPort.requireOwned(bid.getAccountId(), me.getId());
        spotBidService.cancel(id, bid.getAccountId());
        return R.ok();
    }

    @GetMapping("/bids")
    public R<List<SpotBid>> list(@CurrentUser LoginUser me,
                                 @RequestParam("accountId") String accountId,
                                 @RequestParam("sessionId") String sessionId) {
        accountPort.requireOwned(accountId, me.getId());
        return R.ok(spotBidService.query(accountId, sessionId));
    }

    /** 24 点出清结果，全市场公开。 */
    @GetMapping("/clearing")
    public R<List<Map<String, Object>>> clearing(@RequestParam("sessionId") String sessionId) {
        return R.ok(spotBidService.clearingResult(sessionId));
    }

    @GetMapping("/my-result")
    public R<SpotResultVO> myResult(@CurrentUser LoginUser me,
                                    @RequestParam("accountId") String accountId,
                                    @RequestParam("sessionId") String sessionId) {
        accountPort.requireOwned(accountId, me.getId());
        return R.ok(SpotResultVO.of(spotBidService.myBid(accountId, sessionId)));
    }
}
