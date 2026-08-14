package com.github.zhizi01.ets.service.admin;

import com.github.zhizi01.ets.api.AuditPort;
import com.github.zhizi01.ets.common.IdGen;
import com.github.zhizi01.ets.common.PageResult;
import com.github.zhizi01.ets.domain.model.AuditLog;
import com.github.zhizi01.ets.repo.AuditLogRepo;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 审计留痕。任何模块都会调用它，因此这里对异常一律吞掉只打 warn：
 * 记不上日志是可以接受的，把主流程带崩不行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditPort {

    private static final int MAX_DETAIL_LEN = 2000;

    private final AuditLogRepo auditLogRepo;
    private final IdGen idGen;

    @Override
    public void log(String simId, String actorType, String actorId, String actorName,
                    String action, String target, String detail) {
        try {
            AuditLog l = new AuditLog();
            l.setId(idGen.next(IdGen.LOG));
            l.setSimId(simId);
            l.setActorType(actorType == null ? "SYSTEM" : actorType);
            l.setActorId(actorId);
            l.setActorName(actorName);
            l.setAction(action);
            l.setTarget(target);
            l.setDetail(truncate(detail));
            l.setIp(clientIp());
            l.setCreatedAt(System.currentTimeMillis());
            auditLogRepo.saveLog(l);
        } catch (Exception e) {
            log.warn("写审计日志失败 action={}: {}", action, e.getMessage());
        }
    }

    public void admin(String simId, String adminId, String adminName,
                      String action, String target, String detail) {
        log(simId, "ADMIN", adminId, adminName, action, target, detail);
    }

    public PageResult<AuditLog> page(int page, int size) {
        try {
            int p = Math.max(page, 1);
            int s = Math.min(Math.max(size, 1), 200);
            // 审计索引本身有上限（5000 条），直接全量取回内存分页足够
            List<AuditLog> all = auditLogRepo.recent(5000);
            return PageResult.slice(all, p, s);
        } catch (Exception e) {
            log.warn("读取审计日志失败: {}", e.getMessage());
            return PageResult.empty(page, size);
        }
    }

    public List<AuditLog> recent(int limit) {
        try {
            return auditLogRepo.recent(Math.min(Math.max(limit, 1), 500));
        } catch (Exception e) {
            log.warn("读取审计日志失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= MAX_DETAIL_LEN ? s : s.substring(0, MAX_DETAIL_LEN) + "...";
    }

    /** 非 Web 线程（机器人、定时任务）取不到请求上下文，返回 null 即可 */
    private String clientIp() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) {
                HttpServletRequest req = sra.getRequest();
                String xff = req.getHeader("X-Forwarded-For");
                if (xff != null && !xff.isBlank()) {
                    int comma = xff.indexOf(',');
                    return comma > 0 ? xff.substring(0, comma).trim() : xff.trim();
                }
                return req.getRemoteAddr();
            }
        } catch (Exception ignored) {
            // 拿不到 IP 不影响审计本身
        }
        return null;
    }
}
