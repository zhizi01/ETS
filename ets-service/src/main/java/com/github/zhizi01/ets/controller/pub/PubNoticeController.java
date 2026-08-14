package com.github.zhizi01.ets.controller.pub;

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

import java.util.List;

/**
 * 公告与市场事件的公开只读入口。
 * 发布侧在 /api/admin/** 下，而那条路径强制 ADMIN 角色，交易用户读不到；
 * 只靠 WebSocket 推送的话，用户一刷新页面历史公告就没了，所以这里补一个可回溯的读接口。
 */
@RestController
@RequestMapping("/api/pub")
@RequiredArgsConstructor
public class PubNoticeController {

    private final NoticeRepo noticeRepo;
    private final MarketEventRepo marketEventRepo;

    @GetMapping("/notices")
    public R<List<Notice>> notices(@RequestParam String simId,
                                   @RequestParam(defaultValue = "20") int limit) {
        return R.ok(noticeRepo.recentBySim(simId, clamp(limit)));
    }

    /** 全部市场事件，最新在前。 */
    @GetMapping("/events")
    public R<List<MarketEvent>> events(@RequestParam String simId,
                                       @RequestParam(defaultValue = "20") int limit) {
        List<MarketEvent> all = marketEventRepo.listBySim(simId);
        int n = Math.min(clamp(limit), all.size());
        return R.ok(all.subList(0, n));
    }

    /** 当前生效中的事件，用于在盘面上打持续影响的标记。 */
    @GetMapping("/events/active")
    public R<List<MarketEvent>> activeEvents(@RequestParam String simId) {
        return R.ok(marketEventRepo.activeBySim(simId, System.currentTimeMillis()));
    }

    private int clamp(int limit) {
        return Math.min(Math.max(limit, 1), 200);
    }
}
