package com.github.zhizi01.ets.controller.admin;

import com.github.zhizi01.ets.common.PageResult;
import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.dto.SessBatchGenReq;
import com.github.zhizi01.ets.domain.dto.SessCreateReq;
import com.github.zhizi01.ets.domain.dto.SessUpdateReq;
import com.github.zhizi01.ets.domain.dto.SessVO;
import com.github.zhizi01.ets.domain.model.TradingSession;
import com.github.zhizi01.ets.service.sim.SessionPresetFactory;
import com.github.zhizi01.ets.service.sim.TradingSessionService;
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

import java.util.ArrayList;
import java.util.List;

/** 交易场次的排期与手工状态干预。 */
@RestController
@RequestMapping("/api/admin/sessions")
@RequiredArgsConstructor
public class AdminSessionController {

    private final TradingSessionService sessionService;
    private final SessionPresetFactory presetFactory;

    @GetMapping
    public R<PageResult<SessVO>> list(@RequestParam String simId,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return R.ok(PageResult.slice(toVOList(sessionService.listBySim(simId)), page, size));
    }

    @PostMapping
    public R<SessVO> create(@Valid @RequestBody SessCreateReq req) {
        return R.ok(SessVO.of(sessionService.create(req)));
    }

    /**
     * 一键生成标准场次集合：年度/月度/月内多日/日前现货 × 四种交易方式，
     * 外加月度峰谷两场，共 12 个场次，用于快速铺开一场完整演练。
     */
    @PostMapping("/batch-generate")
    public R<List<SessVO>> batchGenerate(@Valid @RequestBody SessBatchGenReq req) {
        List<TradingSession> created = presetFactory.generate(
                req.getSimId(), req.getPreset(), req.isReplaceExisting());
        return R.ok(toVOList(created));
    }

    @GetMapping("/{id}")
    public R<SessVO> detail(@PathVariable String id) {
        return R.ok(SessVO.of(sessionService.require(id)));
    }

    @PutMapping("/{id}")
    public R<SessVO> update(@PathVariable String id, @Valid @RequestBody SessUpdateReq req) {
        return R.ok(SessVO.of(sessionService.update(id, req)));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        sessionService.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/pre-open")
    public R<SessVO> preOpen(@PathVariable String id) {
        return R.ok(SessVO.of(sessionService.preOpen(id)));
    }

    @PostMapping("/{id}/open")
    public R<SessVO> open(@PathVariable String id) {
        return R.ok(SessVO.of(sessionService.open(id)));
    }

    @PostMapping("/{id}/suspend")
    public R<SessVO> suspend(@PathVariable String id) {
        return R.ok(SessVO.of(sessionService.suspend(id)));
    }

    /** 触发集中出清，按 tradeMode 分派给集中竞价或日前现货，出清后自动闭市。 */
    @PostMapping("/{id}/clear")
    public R<SessVO> clear(@PathVariable String id) {
        return R.ok(SessVO.of(sessionService.clear(id)));
    }

    @PostMapping("/{id}/close")
    public R<SessVO> close(@PathVariable String id) {
        return R.ok(SessVO.of(sessionService.close(id)));
    }

    private static List<SessVO> toVOList(List<TradingSession> list) {
        List<SessVO> out = new ArrayList<>(list.size());
        for (TradingSession s : list) {
            out.add(SessVO.of(s));
        }
        return out;
    }
}
