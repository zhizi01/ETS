package com.github.zhizi01.ets.controller.admin;

import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.dto.SimRankVO;
import com.github.zhizi01.ets.domain.model.Position;
import com.github.zhizi01.ets.domain.model.TradingAccount;
import com.github.zhizi01.ets.repo.AccountRepo;
import com.github.zhizi01.ets.repo.PositionRepo;
import com.github.zhizi01.ets.service.sim.SimServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端的只读洞察接口，补齐监控大屏与参与者管理需要、而其它控制器没有覆盖的两项数据。
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminInsightController {

    private final SimServiceImpl simService;
    private final AccountRepo accountRepo;
    private final PositionRepo positionRepo;

    /** 与 /api/pub/rank 同源，走管理端鉴权、默认取更长的榜单。 */
    @GetMapping("/rank")
    public R<List<SimRankVO>> rank(@RequestParam String simId,
                                   @RequestParam(defaultValue = "100") int limit) {
        return R.ok(simService.rank(simId, Math.min(Math.max(limit, 1), 500)));
    }

    /**
     * 任意账户的逐标的持仓。用户端的同名接口只允许查自己的账户，
     * 管理员做风险排查时需要看别人的仓位。
     */
    @GetMapping("/accounts/{id}/positions")
    public R<List<Position>> positions(@PathVariable String id) {
        TradingAccount a = accountRepo.find(id);
        if (a == null) {
            return R.fail(ErrorCode.ACCOUNT_NOT_FOUND);
        }
        return R.ok(positionRepo.listByAccount(id));
    }
}
