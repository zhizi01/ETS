package com.github.zhizi01.ets.controller.user;

import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.model.MarketEvent;
import com.github.zhizi01.ets.domain.model.Notice;
import com.github.zhizi01.ets.repo.MarketEventRepo;
import com.github.zhizi01.ets.repo.NoticeRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户端公告与事件回溯。公开接口 /api/pub/notices 已能读历史，
 * 这里再给登录用户一条契约路径，刷新页面后不必只靠 WebSocket。
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserNoticeController {

    private final NoticeRepo noticeRepo;
    private final MarketEventRepo marketEventRepo;

    @GetMapping("/notices")
    public R<List<Notice>> notices(@RequestParam String simId,
                                   @RequestParam(defaultValue = "20") int limit) {
        return R.ok(noticeRepo.recentBySim(simId, clamp(limit)));
    }

    /** 公告 + 事件一次带回，方便用户端公告页首屏填充。 */
    @GetMapping("/notices/feed")
    public R<Map<String, Object>> feed(@RequestParam String simId,
                                       @RequestParam(defaultValue = "20") int limit) {
        int n = clamp(limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("notices", noticeRepo.recentBySim(simId, n));
        List<MarketEvent> events = marketEventRepo.listBySim(simId);
        out.put("events", events.subList(0, Math.min(n, events.size())));
        return R.ok(out);
    }

    private int clamp(int limit) {
        return Math.min(Math.max(limit, 1), 200);
    }
}
