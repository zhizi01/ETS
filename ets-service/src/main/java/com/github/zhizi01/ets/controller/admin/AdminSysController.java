package com.github.zhizi01.ets.controller.admin;

import com.github.zhizi01.ets.common.PageResult;
import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.dto.SysMetricsVO;
import com.github.zhizi01.ets.domain.model.AuditLog;
import com.github.zhizi01.ets.service.admin.AuditServiceImpl;
import com.github.zhizi01.ets.service.admin.SysMetricsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 管理端系统面板：运行指标与审计日志，对应 docs/API.md 3.6。 */
@RestController
@RequestMapping("/api/admin/sys")
@RequiredArgsConstructor
public class AdminSysController {

    private final SysMetricsService sysMetricsService;
    private final AuditServiceImpl auditService;

    @GetMapping("/metrics")
    public R<SysMetricsVO> metrics() {
        return R.ok(sysMetricsService.metrics());
    }

    @GetMapping("/audit")
    public R<PageResult<AuditLog>> audit(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return R.ok(auditService.page(page, size));
    }
}
