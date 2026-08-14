package com.github.zhizi01.ets.controller.user;

import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.dto.SessVO;
import com.github.zhizi01.ets.domain.enums.TSessionState;
import com.github.zhizi01.ets.domain.enums.TradeMode;
import com.github.zhizi01.ets.domain.enums.Variety;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.service.sim.TradingSessionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** 登录用户查看本盘场次，支持按品种 / 交易方式 / 状态筛选。 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserSessionController {

    private final TradingSessionService sessionService;

    @GetMapping("/sessions")
    public R<List<SessVO>> list(@RequestParam String simId,
                                @RequestParam(required = false) Variety variety,
                                @RequestParam(required = false) TradeMode tradeMode,
                                @RequestParam(required = false) TSessionState state) {
        List<SessVO> out = new ArrayList<>();
        for (TradingSession s : sessionService.listBySim(simId)) {
            if (variety != null && s.getVariety() != variety) {
                continue;
            }
            if (tradeMode != null && s.getTradeMode() != tradeMode) {
                continue;
            }
            if (state != null && s.getState() != state) {
                continue;
            }
            out.add(SessVO.of(s));
        }
        return R.ok(out);
    }

    @GetMapping("/sessions/{id}")
    public R<SessVO> detail(@PathVariable String id) {
        return R.ok(SessVO.of(sessionService.require(id)));
    }
}
