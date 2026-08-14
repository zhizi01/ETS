package com.github.zhizi01.ets.service.admin;

import com.github.zhizi01.ets.api.WsPort;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.IdGen;
import com.github.zhizi01.ets.common.Nums;
import com.github.zhizi01.ets.common.PageResult;
import com.github.zhizi01.ets.domain.dto.EventCreateReq;
import com.github.zhizi01.ets.domain.enums.NoticeLevel;
import com.github.zhizi01.ets.domain.model.MarketEvent;
import com.github.zhizi01.ets.repo.MarketEventRepo;
import com.github.zhizi01.ets.service.market.FundamentalService;
import com.github.zhizi01.ets.ws.WsTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 市场事件干预。事件写入后立即重算基本面，机器人在下一 tick 就会按新的基准价报价，
 * 行情随之出现方向性波动——这是管理端"制造行情"的主要手段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketEventService {

    private static final int DEFAULT_DURATION_MINUTES = 15;

    private final MarketEventRepo eventRepo;
    private final IdGen idGen;
    private final FundamentalService fundamentalService;
    private final NoticeService noticeService;
    private final WsPort wsPort;
    private final AuditServiceImpl auditService;

    public MarketEvent create(EventCreateReq req, String adminId, String adminName) {
        if (req == null || req.getSimId() == null || req.getSimId().isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "simId 不能为空");
        }
        if (req.getType() == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "事件类型不能为空");
        }
        long now = System.currentTimeMillis();
        int minutes = req.getDurationMinutes() == null || req.getDurationMinutes() <= 0
                ? DEFAULT_DURATION_MINUTES : Math.min(req.getDurationMinutes(), 24 * 60);

        MarketEvent e = new MarketEvent();
        e.setId(idGen.next(IdGen.EVENT));
        e.setSimId(req.getSimId());
        e.setType(req.getType());
        e.setMagnitude(req.getMagnitude() == null ? 1.0 : Nums.clamp(req.getMagnitude(), -1, 1));
        e.setTitle(req.getTitle() == null || req.getTitle().isBlank()
                ? req.getType().getLabel() : req.getTitle().trim());
        e.setDescription(req.getDescription());
        e.setEffectiveAt(now);
        e.setExpireAt(now + minutes * 60_000L);
        e.setCreatedByAdminId(adminId);
        e.setCreatedAt(now);
        eventRepo.saveEvent(e);

        fundamentalService.recompute(e.getSimId());
        wsPort.push(WsTopics.sim(e.getSimId()), "event", e);
        noticeService.system(e.getSimId(), "【市场事件】" + e.getTitle(), buildNotice(e, minutes), NoticeLevel.WARN);
        auditService.admin(e.getSimId(), adminId, adminName, "注入市场事件", e.getId(),
                e.getType().name() + " magnitude=" + e.getMagnitude() + " 持续 " + minutes + " 分钟");
        log.info("模拟盘 {} 注入市场事件 {} 冲击 {}", e.getSimId(), e.getType(), e.priceImpact());
        return e;
    }

    public PageResult<MarketEvent> page(String simId, int page, int size) {
        List<MarketEvent> all = list(simId);
        return PageResult.slice(all, Math.max(page, 1), Math.min(Math.max(size, 1), 200));
    }

    public List<MarketEvent> list(String simId) {
        try {
            return eventRepo.listBySim(simId);
        } catch (Exception e) {
            log.warn("读取市场事件失败 {}: {}", simId, e.getMessage());
            return List.of();
        }
    }

    public List<MarketEvent> active(String simId) {
        try {
            return eventRepo.activeBySim(simId, System.currentTimeMillis());
        } catch (Exception e) {
            return List.of();
        }
    }

    public void delete(String eventId, String adminId, String adminName) {
        MarketEvent e = eventRepo.find(eventId);
        if (e == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "市场事件不存在");
        }
        eventRepo.removeEvent(e);
        fundamentalService.recompute(e.getSimId());
        wsPort.push(WsTopics.sim(e.getSimId()), "eventRemoved", e);
        auditService.admin(e.getSimId(), adminId, adminName, "撤销市场事件", eventId, e.getTitle());
    }

    private String buildNotice(MarketEvent e, int minutes) {
        String impact = Nums.round(e.priceImpact() * 100, 1) + "%";
        String body = e.getDescription() == null || e.getDescription().isBlank() ? "" : e.getDescription() + " ";
        return body + "预计对基准电价形成 " + impact + " 的冲击，持续约 " + minutes + " 分钟。";
    }
}
