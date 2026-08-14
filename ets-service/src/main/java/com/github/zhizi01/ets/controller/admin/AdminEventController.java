package com.github.zhizi01.ets.controller.admin;

import com.github.zhizi01.ets.common.PageResult;
import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.dto.EventCreateReq;
import com.github.zhizi01.ets.domain.dto.FundamentalUpdateReq;
import com.github.zhizi01.ets.domain.dto.NoticeCreateReq;
import com.github.zhizi01.ets.domain.model.Fundamental;
import com.github.zhizi01.ets.domain.model.MarketEvent;
import com.github.zhizi01.ets.domain.model.Notice;
import com.github.zhizi01.ets.security.CurrentUser;
import com.github.zhizi01.ets.security.LoginUser;
import com.github.zhizi01.ets.service.admin.MarketEventService;
import com.github.zhizi01.ets.service.admin.NoticeService;
import com.github.zhizi01.ets.service.market.FundamentalService;
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

/** 管理端市场干预：事件注入、基本面调参、公告发布，对应 docs/API.md 3.5。 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminEventController {

    private final MarketEventService marketEventService;
    private final FundamentalService fundamentalService;
    private final NoticeService noticeService;

    @GetMapping("/events")
    public R<PageResult<MarketEvent>> events(@RequestParam String simId,
                                             @RequestParam(defaultValue = "1") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return R.ok(marketEventService.page(simId, page, size));
    }

    @GetMapping("/events/active")
    public R<List<MarketEvent>> activeEvents(@RequestParam String simId) {
        return R.ok(marketEventService.active(simId));
    }

    @PostMapping("/events")
    public R<MarketEvent> createEvent(@RequestBody EventCreateReq req, @CurrentUser LoginUser admin) {
        return R.ok(marketEventService.create(req, admin.getId(), admin.getNickname()));
    }

    @DeleteMapping("/events/{id}")
    public R<Void> deleteEvent(@PathVariable String id, @CurrentUser LoginUser admin) {
        marketEventService.delete(id, admin.getId(), admin.getNickname());
        return R.ok();
    }

    @GetMapping("/fundamental")
    public R<Fundamental> fundamental(@RequestParam String simId) {
        return R.ok(fundamentalService.get(simId));
    }

    @PutMapping("/fundamental")
    public R<Fundamental> updateFundamental(@RequestParam String simId,
                                            @RequestBody FundamentalUpdateReq req) {
        return R.ok(fundamentalService.update(simId, req));
    }

    @PostMapping("/notices")
    public R<Notice> createNotice(@RequestBody NoticeCreateReq req, @CurrentUser LoginUser admin) {
        return R.ok(noticeService.create(req, admin.getId(), admin.getNickname()));
    }

    @GetMapping("/notices")
    public R<List<Notice>> notices(@RequestParam String simId,
                                   @RequestParam(defaultValue = "20") int limit) {
        return R.ok(noticeService.recent(simId, limit));
    }
}
