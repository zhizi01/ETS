package com.github.zhizi01.ets.controller.pub;

import com.github.zhizi01.ets.api.Depth;
import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.dto.KlineVO;
import com.github.zhizi01.ets.domain.dto.MarketTradeVO;
import com.github.zhizi01.ets.domain.dto.QuoteVO;
import com.github.zhizi01.ets.domain.model.Quote;
import com.github.zhizi01.ets.repo.TradeRepo;
import com.github.zhizi01.ets.service.market.DepthService;
import com.github.zhizi01.ets.service.market.KlineService;
import com.github.zhizi01.ets.service.market.QuoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/** 公开行情接口，免鉴权。 */
@RestController
@RequestMapping("/api/pub/market")
@RequiredArgsConstructor
public class PubMarketController {

    private final QuoteService quoteService;
    private final KlineService klineService;
    private final DepthService depthService;
    private final TradeRepo tradeRepo;

    @GetMapping("/quotes")
    public R<List<QuoteVO>> quotes(@RequestParam String simId) {
        List<QuoteVO> out = new ArrayList<>();
        for (Quote q : quoteService.listBySim(simId)) {
            QuoteVO vo = QuoteVO.of(q);
            if (vo != null) {
                out.add(vo);
            }
        }
        out.sort((a, b) -> a.getInstrumentKey().compareTo(b.getInstrumentKey()));
        return R.ok(out);
    }

    @GetMapping("/quote")
    public R<QuoteVO> quote(@RequestParam String simId, @RequestParam String instrumentKey) {
        return R.ok(QuoteVO.of(quoteService.get(simId, instrumentKey)));
    }

    @GetMapping("/kline")
    public R<List<KlineVO>> kline(@RequestParam String simId,
                                  @RequestParam String instrumentKey,
                                  @RequestParam(defaultValue = "1m") String interval,
                                  @RequestParam(defaultValue = "200") int limit) {
        int n = Math.min(Math.max(limit, 1), 1000);
        return R.ok(KlineVO.of(klineService.recent(simId, instrumentKey, interval, n)));
    }

    @GetMapping("/depth")
    public R<Depth> depth(@RequestParam String sessionId,
                          @RequestParam(defaultValue = "5") int level) {
        return R.ok(depthService.depth(sessionId, level));
    }

    @GetMapping("/trades")
    public R<List<MarketTradeVO>> trades(@RequestParam String sessionId,
                                         @RequestParam(defaultValue = "50") int limit) {
        int n = Math.min(Math.max(limit, 1), 500);
        return R.ok(MarketTradeVO.of(tradeRepo.recentBySession(sessionId, n)));
    }
}
