package com.github.zhizi01.ets.service.admin;

import com.github.zhizi01.ets.api.WsPort;
import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.common.IdGen;
import com.github.zhizi01.ets.domain.dto.NoticeCreateReq;
import com.github.zhizi01.ets.domain.enums.NoticeLevel;
import com.github.zhizi01.ets.domain.model.Notice;
import com.github.zhizi01.ets.repo.NoticeRepo;
import com.github.zhizi01.ets.ws.WsTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/** 市场公告。发布后即时推给该盘的全部订阅者。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoticeService {

    private final NoticeRepo noticeRepo;
    private final IdGen idGen;
    private final WsPort wsPort;
    private final AuditServiceImpl auditService;

    public Notice create(NoticeCreateReq req, String adminId, String adminName) {
        if (req == null || req.getSimId() == null || req.getSimId().isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "simId 不能为空");
        }
        if (req.getTitle() == null || req.getTitle().isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "公告标题不能为空");
        }
        Notice n = new Notice();
        n.setId(idGen.next(IdGen.NOTICE));
        n.setSimId(req.getSimId());
        n.setTitle(req.getTitle().trim());
        n.setContent(req.getContent());
        n.setLevel(req.getLevel() == null ? NoticeLevel.INFO : req.getLevel());
        n.setCreatedByAdminId(adminId);
        n.setCreatedAt(System.currentTimeMillis());
        noticeRepo.saveNotice(n);

        wsPort.push(WsTopics.sim(n.getSimId()), "notice", n);
        auditService.admin(n.getSimId(), adminId, adminName, "发布公告", n.getId(), n.getTitle());
        return n;
    }

    /** 系统自动公告，例如市场事件生效时同步广播一条 */
    public Notice system(String simId, String title, String content, NoticeLevel level) {
        try {
            Notice n = new Notice();
            n.setId(idGen.next(IdGen.NOTICE));
            n.setSimId(simId);
            n.setTitle(title);
            n.setContent(content);
            n.setLevel(level == null ? NoticeLevel.INFO : level);
            n.setCreatedAt(System.currentTimeMillis());
            noticeRepo.saveNotice(n);
            wsPort.push(WsTopics.sim(simId), "notice", n);
            return n;
        } catch (Exception e) {
            log.warn("系统公告发布失败 {}: {}", title, e.getMessage());
            return null;
        }
    }

    public List<Notice> recent(String simId, int limit) {
        try {
            return noticeRepo.recentBySim(simId, Math.min(Math.max(limit, 1), 200));
        } catch (Exception e) {
            log.warn("读取公告失败 {}: {}", simId, e.getMessage());
            return List.of();
        }
    }
}
