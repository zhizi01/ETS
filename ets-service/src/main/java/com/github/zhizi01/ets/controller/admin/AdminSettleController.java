package com.github.zhizi01.ets.controller.admin;

import com.github.zhizi01.ets.api.ClearingPort;
import com.github.zhizi01.ets.common.PageResult;
import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.dto.SettleRunReq;
import com.github.zhizi01.ets.domain.model.Bill;
import com.github.zhizi01.ets.repo.BillRepo;
import com.github.zhizi01.ets.service.clearing.SettlementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 管理端结算（docs/API.md 3.6）。 */
@RestController
@RequestMapping("/api/admin/settle")
@RequiredArgsConstructor
public class AdminSettleController {

    private final ClearingPort clearingPort;
    private final BillRepo billRepo;

    @PostMapping("/run")
    public R<List<Bill>> run(@Valid @RequestBody SettleRunReq req) {
        String period = req.getPeriod() == null || req.getPeriod().isBlank()
                ? SettlementService.PERIOD_FINAL : req.getPeriod().trim();
        return R.ok(clearingPort.settle(req.getSimId(), period));
    }

    @GetMapping("/bills")
    public R<PageResult<Bill>> bills(@RequestParam("simId") String simId,
                                     @RequestParam(value = "period", required = false) String period,
                                     @RequestParam(value = "page", defaultValue = "1") int page,
                                     @RequestParam(value = "size", defaultValue = "20") int size) {
        String p = period == null || period.isBlank() ? SettlementService.PERIOD_FINAL : period.trim();
        // ZSET 的 score 是 -profit，取升序即利润从高到低
        return R.ok(PageResult.slice(billRepo.listBySimPeriod(simId, p), page, size));
    }
}
