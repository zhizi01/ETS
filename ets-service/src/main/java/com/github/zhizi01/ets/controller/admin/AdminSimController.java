package com.github.zhizi01.ets.controller.admin;

import com.github.zhizi01.ets.common.PageResult;
import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.dto.SimCreateReq;
import com.github.zhizi01.ets.domain.dto.SimDashboardVO;
import com.github.zhizi01.ets.domain.dto.SimUpdateReq;
import com.github.zhizi01.ets.domain.dto.SimVO;
import com.github.zhizi01.ets.security.CurrentUser;
import com.github.zhizi01.ets.security.LoginUser;
import com.github.zhizi01.ets.service.sim.SimServiceImpl;
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

/** 模拟盘的创建与生命周期操作。 */
@RestController
@RequestMapping("/api/admin/sims")
@RequiredArgsConstructor
public class AdminSimController {

    private final SimServiceImpl simService;

    @GetMapping
    public R<PageResult<SimVO>> list(@RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return R.ok(PageResult.slice(simService.toVOList(simService.listAll()), page, size));
    }

    @PostMapping
    public R<SimVO> create(@CurrentUser LoginUser me, @Valid @RequestBody SimCreateReq req) {
        return R.ok(simService.toVO(simService.create(req, me.getId())));
    }

    @GetMapping("/{id}")
    public R<SimVO> detail(@PathVariable String id) {
        return R.ok(simService.toVO(simService.requireSim(id)));
    }

    @PutMapping("/{id}")
    public R<SimVO> update(@PathVariable String id, @Valid @RequestBody SimUpdateReq req) {
        return R.ok(simService.toVO(simService.update(id, req)));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        simService.delete(id);
        return R.ok();
    }

    /** 开盘：注资、锁定全部账户参数、重排场次时间轴、唤起机器人。 */
    @PostMapping("/{id}/start")
    public R<SimVO> start(@PathVariable String id) {
        return R.ok(simService.toVO(simService.start(id)));
    }

    @PostMapping("/{id}/pause")
    public R<SimVO> pause(@PathVariable String id) {
        return R.ok(simService.toVO(simService.pause(id)));
    }

    @PostMapping("/{id}/resume")
    public R<SimVO> resume(@PathVariable String id) {
        return R.ok(simService.toVO(simService.resume(id)));
    }

    @PostMapping("/{id}/end")
    public R<SimVO> end(@PathVariable String id) {
        return R.ok(simService.toVO(simService.end(id)));
    }

    /** 清空该盘全部交易数据并回到筹备中，账户需要重新创建。 */
    @PostMapping("/{id}/reset")
    public R<SimVO> reset(@PathVariable String id) {
        return R.ok(simService.toVO(simService.reset(id)));
    }

    @GetMapping("/{id}/dashboard")
    public R<SimDashboardVO> dashboard(@PathVariable String id) {
        return R.ok(simService.dashboard(id));
    }
}
