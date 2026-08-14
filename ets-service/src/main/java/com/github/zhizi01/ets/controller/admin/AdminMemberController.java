package com.github.zhizi01.ets.controller.admin;

import com.github.zhizi01.ets.api.FundPort;
import com.github.zhizi01.ets.api.TradePort;
import com.github.zhizi01.ets.common.PageResult;
import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.dto.AccountVO;
import com.github.zhizi01.ets.domain.dto.AdminFundAdjustReq;
import com.github.zhizi01.ets.domain.dto.AdminPwdResetReq;
import com.github.zhizi01.ets.domain.dto.UserVO;
import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.enums.OrderSource;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.domain.model.User;
import com.github.zhizi01.ets.repo.AccountRepo;
import com.github.zhizi01.ets.service.account.TradingAccountServiceImpl;
import com.github.zhizi01.ets.service.sim.SimServiceImpl;
import com.github.zhizi01.ets.service.user.UserService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 参与者管理：交易用户的状态与口令、交易账户的资金与挂单干预。 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
public class AdminMemberController {

    private final UserService userService;
    private final TradingAccountServiceImpl accountService;
    private final SimServiceImpl simService;
    private final AccountRepo accountRepo;
    private final FundPort fundPort;
    private final TradePort tradePort;

    public AdminMemberController(UserService userService,
                                 TradingAccountServiceImpl accountService,
                                 SimServiceImpl simService,
                                 AccountRepo accountRepo,
                                 FundPort fundPort,
                                 @Lazy TradePort tradePort) {
        this.userService = userService;
        this.accountService = accountService;
        this.simService = simService;
        this.accountRepo = accountRepo;
        this.fundPort = fundPort;
        this.tradePort = tradePort;
    }

    // ══════════════════════════ 交易用户 ══════════════════════════

    @GetMapping("/users")
    public R<PageResult<UserVO>> users(@RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        List<User> matched = userService.search(keyword);
        List<UserVO> vos = new ArrayList<>(matched.size());
        for (User u : matched) {
            vos.add(UserVO.of(u));
        }
        PageResult<UserVO> result = PageResult.slice(vos, page, size);
        // 账户数要遍历全部模拟盘，只给当前页补，避免用户多时把 Redis 打满
        fillAccountCount(result.getList());
        return R.ok(result);
    }

    private void fillAccountCount(List<UserVO> pageItems) {
        if (pageItems.isEmpty()) {
            return;
        }
        List<Sim> sims = simService.listAll();
        for (UserVO vo : pageItems) {
            long n = 0;
            for (Sim sim : sims) {
                n += accountRepo.countByUser(vo.getId(), sim.getId());
            }
            vo.setAccountCount((int) n);
        }
    }

    @PostMapping("/users/{id}/freeze")
    public R<UserVO> freeze(@PathVariable String id) {
        return R.ok(UserVO.of(userService.setFrozen(id, true)));
    }

    @PostMapping("/users/{id}/unfreeze")
    public R<UserVO> unfreeze(@PathVariable String id) {
        return R.ok(UserVO.of(userService.setFrozen(id, false)));
    }

    /** 返回实际生效的明文口令，管理端展示一次后应提示线下转达。 */
    @PostMapping("/users/{id}/reset-password")
    public R<Map<String, Object>> resetPassword(@PathVariable String id,
                                                @Valid @RequestBody(required = false) AdminPwdResetReq req) {
        String pwd = userService.resetPassword(id, req == null ? null : req.getNewPassword());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userId", id);
        out.put("password", pwd);
        return R.ok(out);
    }

    // ══════════════════════════ 交易账户 ══════════════════════════

    @GetMapping("/accounts")
    public R<PageResult<AccountVO>> accounts(@RequestParam String simId,
                                             @RequestParam(required = false) MemberType memberType,
                                             @RequestParam(required = false) OrderSource source,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        List<TradingAccount> matched = new ArrayList<>();
        for (TradingAccount a : accountService.listBySim(simId, memberType)) {
            if (source == null || source == a.getSource()) {
                matched.add(a);
            }
        }
        return R.ok(PageResult.slice(accountService.toVOList(matched), page, size));
    }

    @PostMapping("/accounts/{id}/adjust-fund")
    public R<AccountVO> adjustFund(@PathVariable String id, @Valid @RequestBody AdminFundAdjustReq req) {
        TradingAccount a = accountService.require(id);
        fundPort.adjust(id, req.getAmount(),
                req.getRemark() == null || req.getRemark().isBlank() ? "管理员调整资金" : req.getRemark());
        log.info("管理员调整账户 {} 资金 {} 元", a.getCode(), req.getAmount());
        return R.ok(accountService.toVO(accountService.require(id)));
    }

    /** 撤销该账户在本模拟盘所有场次上的挂单，用于处置异常报价或释放冻结资金。 */
    @PostMapping("/accounts/{id}/force-cancel")
    public R<Map<String, Object>> forceCancel(@PathVariable String id) {
        TradingAccount a = accountService.require(id);
        int cancelled = 0;
        for (TradingSession s : simService.listSessions(a.getSimId())) {
            try {
                cancelled += tradePort.cancelAll(id, s.getId());
            } catch (Exception e) {
                log.warn("撤销账户 {} 在场次 {} 的挂单失败：{}", a.getCode(), s.getId(), e.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("accountId", id);
        out.put("cancelled", cancelled);
        return R.ok(out);
    }
}
