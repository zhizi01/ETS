package com.github.zhizi01.ets.controller.user;

import com.github.zhizi01.ets.common.PageResult;
import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.dto.AccountVO;
import com.github.zhizi01.ets.domain.dto.AcctCreateReq;
import com.github.zhizi01.ets.domain.dto.AcctSummaryVO;
import com.github.zhizi01.ets.domain.dto.AcctUpdateReq;
import com.github.zhizi01.ets.domain.model.FundFlow;
import com.github.zhizi01.ets.domain.model.Position;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.repo.FundFlowRepo;
import com.github.zhizi01.ets.security.CurrentUser;
import com.github.zhizi01.ets.security.LoginUser;
import com.github.zhizi01.ets.service.account.TradingAccountServiceImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户自己的交易账户。增删改都只在模拟盘 SETUP 阶段开放，
 * 开盘后 params 被锁死，服务层会直接拒绝，这里不做重复判断。
 */
@RestController
@RequestMapping("/api/user/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final TradingAccountServiceImpl accountService;
    private final FundFlowRepo fundFlowRepo;

    @GetMapping
    public R<List<AccountVO>> list(@CurrentUser LoginUser me, @RequestParam String simId) {
        return R.ok(accountService.toVOList(accountService.listMine(me.getId(), simId)));
    }

    @PostMapping
    public R<AccountVO> create(@CurrentUser LoginUser me, @Valid @RequestBody AcctCreateReq req) {
        TradingAccount a = accountService.create(me.getId(), req);
        return R.ok(accountService.toVO(a));
    }

    @GetMapping("/{id}")
    public R<AccountVO> detail(@CurrentUser LoginUser me, @PathVariable String id) {
        return R.ok(accountService.toVO(accountService.requireOwned(id, me.getId())));
    }

    @PutMapping("/{id}")
    public R<AccountVO> update(@CurrentUser LoginUser me, @PathVariable String id,
                               @Valid @RequestBody AcctUpdateReq req) {
        return R.ok(accountService.toVO(accountService.update(id, me.getId(), req)));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@CurrentUser LoginUser me, @PathVariable String id) {
        accountService.delete(id, me.getId());
        return R.ok();
    }

    @GetMapping("/{id}/positions")
    public R<List<Position>> positions(@CurrentUser LoginUser me, @PathVariable String id) {
        accountService.requireOwned(id, me.getId());
        return R.ok(accountService.positions(id));
    }

    @GetMapping("/{id}/flows")
    public R<PageResult<FundFlow>> flows(@CurrentUser LoginUser me, @PathVariable String id,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        accountService.requireOwned(id, me.getId());
        return R.ok(PageResult.slice(fundFlowRepo.listAllByAccount(id), page, size));
    }

    @GetMapping("/{id}/summary")
    public R<AcctSummaryVO> summary(@CurrentUser LoginUser me, @PathVariable String id) {
        accountService.requireOwned(id, me.getId());
        return R.ok(accountService.summary(id));
    }
}
