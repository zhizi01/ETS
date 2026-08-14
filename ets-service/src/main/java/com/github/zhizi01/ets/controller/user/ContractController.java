package com.github.zhizi01.ets.controller.user;

import com.github.zhizi01.ets.api.AccountPort;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.PageResult;
import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.enums.ContractStatus;
import com.github.zhizi01.ets.domain.model.Bill;
import com.github.zhizi01.ets.domain.model.Contract;
import com.github.zhizi01.ets.repo.BillRepo;
import com.github.zhizi01.ets.security.CurrentUser;
import com.github.zhizi01.ets.security.LoginUser;
import com.github.zhizi01.ets.service.clearing.ContractService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** 购售电合同与结算单（docs/API.md 2.8）。 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class ContractController {

    private final ContractService contractService;
    private final BillRepo billRepo;

    @Lazy
    @Autowired
    private AccountPort accountPort;

    @GetMapping("/contracts")
    public R<PageResult<Contract>> contracts(@CurrentUser LoginUser me,
                                             @RequestParam("accountId") String accountId,
                                             @RequestParam(value = "status", required = false) String status,
                                             @RequestParam(value = "page", defaultValue = "1") int page,
                                             @RequestParam(value = "size", defaultValue = "20") int size) {
        accountPort.requireOwned(accountId, me.getId());
        ContractStatus st = parseStatus(status);
        List<Contract> out = new ArrayList<>();
        for (Contract c : contractService.listByAccount(accountId)) {
            if (c != null && (st == null || c.getStatus() == st)) {
                out.add(c);
            }
        }
        return R.ok(PageResult.slice(out, page, size));
    }

    @GetMapping("/bills")
    public R<PageResult<Bill>> bills(@CurrentUser LoginUser me,
                                     @RequestParam("accountId") String accountId,
                                     @RequestParam(value = "period", required = false) String period,
                                     @RequestParam(value = "page", defaultValue = "1") int page,
                                     @RequestParam(value = "size", defaultValue = "20") int size) {
        accountPort.requireOwned(accountId, me.getId());
        List<Bill> out = new ArrayList<>();
        for (Bill b : billRepo.listByAccount(accountId)) {
            if (b != null && (period == null || period.isBlank() || period.equals(b.getPeriod()))) {
                out.add(b);
            }
        }
        return R.ok(PageResult.slice(out, page, size));
    }

    private ContractStatus parseStatus(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return ContractStatus.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "未知的合同状态: " + s);
        }
    }
}
