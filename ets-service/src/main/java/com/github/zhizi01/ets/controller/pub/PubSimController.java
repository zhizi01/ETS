package com.github.zhizi01.ets.controller.pub;

import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.dto.SessVO;
import com.github.zhizi01.ets.domain.dto.SimRankVO;
import com.github.zhizi01.ets.domain.dto.SimVO;
import com.github.zhizi01.ets.domain.model.Sim;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.service.sim.SimServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 免鉴权的演练场与场次信息。登录页与大屏在拿到 token 之前就要展示这些内容，
 * 因此放在 /api/pub 下开放读取。
 */
@RestController
@RequestMapping("/api/pub")
@RequiredArgsConstructor
public class PubSimController {

    private final SimServiceImpl simService;

    @GetMapping("/sims")
    public R<List<SimVO>> sims() {
        return R.ok(simService.toVOList(simService.listAll()));
    }

    @GetMapping("/sims/{simId}")
    public R<SimVO> sim(@PathVariable String simId) {
        Sim sim = simService.requireSim(simId);
        return R.ok(simService.toVO(sim));
    }

    @GetMapping("/sessions")
    public R<List<SessVO>> sessions(@RequestParam String simId) {
        List<TradingSession> list = simService.listSessions(simId);
        List<SessVO> out = new ArrayList<>(list.size());
        for (TradingSession s : list) {
            out.add(SessVO.of(s));
        }
        return R.ok(out);
    }

    @GetMapping("/rank")
    public R<List<SimRankVO>> rank(@RequestParam String simId,
                                   @RequestParam(defaultValue = "50") int limit) {
        return R.ok(simService.rank(simId, Math.min(Math.max(limit, 1), 200)));
    }
}
