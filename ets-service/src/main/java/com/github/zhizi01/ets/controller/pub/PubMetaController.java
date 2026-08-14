package com.github.zhizi01.ets.controller.pub;

import com.github.zhizi01.ets.common.R;
import com.github.zhizi01.ets.domain.enums.GenType;
import com.github.zhizi01.ets.domain.enums.LabeledEnum;
import com.github.zhizi01.ets.domain.enums.PeriodType;
import com.github.zhizi01.ets.domain.enums.RobotStrategy;
import com.github.zhizi01.ets.repo.RedisStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 健康检查与前端枚举字典。 */
@Slf4j
@RestController
@RequestMapping("/api/pub")
@RequiredArgsConstructor
public class PubMetaController {

    private static final String ENUM_PACKAGE = "com.github.zhizi01.ets.domain.enums";

    private final RedisStore store;

    /** 枚举是编译期常量，扫描一次即可，之后直接吐缓存。 */
    private volatile Map<String, List<Map<String, Object>>> enumCache;

    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        boolean redisOk;
        try {
            Boolean pong = store.raw().execute(
                    (RedisCallback<Boolean>) conn -> "PONG".equalsIgnoreCase(conn.ping()));
            redisOk = Boolean.TRUE.equals(pong);
        } catch (Exception e) {
            log.warn("Redis 健康检查失败：{}", e.getMessage());
            redisOk = false;
        }
        out.put("redis", redisOk);
        out.put("latencyMs", System.currentTimeMillis() - start);
        out.put("ts", System.currentTimeMillis());
        return R.ok(out);
    }

    @GetMapping("/meta/enums")
    public R<Map<String, List<Map<String, Object>>>> enums() {
        return R.ok(dict());
    }

    private Map<String, List<Map<String, Object>>> dict() {
        Map<String, List<Map<String, Object>>> local = enumCache;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (enumCache == null) {
                enumCache = scanEnums();
            }
            return enumCache;
        }
    }

    /**
     * 反射 domain/enums 包下所有 {@link LabeledEnum} 实现，输出 {@code {类名: [{value,label,...}]}}。
     * 新增枚举无需改这里，前端字典自动跟进。
     */
    private Map<String, List<Map<String, Object>>> scanEnums() {
        Map<String, List<Map<String, Object>>> out = new TreeMap<>();
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(LabeledEnum.class));
        for (BeanDefinition bd : scanner.findCandidateComponents(ENUM_PACKAGE)) {
            String className = bd.getBeanClassName();
            if (className == null) {
                continue;
            }
            try {
                Class<?> clazz = Class.forName(className);
                if (!clazz.isEnum() || !LabeledEnum.class.isAssignableFrom(clazz)) {
                    continue;
                }
                Object[] constants = clazz.getEnumConstants();
                if (constants == null) {
                    continue;
                }
                List<Map<String, Object>> items = new ArrayList<>(constants.length);
                for (Object c : constants) {
                    items.add(describe((LabeledEnum) c));
                }
                out.put(clazz.getSimpleName(), items);
            } catch (Exception e) {
                log.warn("枚举 {} 反射失败，已跳过：{}", className, e.getMessage());
            }
        }
        if (out.isEmpty()) {
            log.warn("未扫描到任何 LabeledEnum 实现，前端字典将为空");
        }
        return out;
    }

    /** 除 value/label 外，把前端定价与选型真正用得上的附加属性一并带出。 */
    private Map<String, Object> describe(LabeledEnum e) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("value", e.name());
        item.put("label", e.getLabel());
        if (e instanceof GenType g) {
            item.put("typicalCost", g.getTypicalCost());
            item.put("renewable", g.isRenewable());
        } else if (e instanceof PeriodType p) {
            item.put("priceFactor", p.getPriceFactor());
        } else if (e instanceof RobotStrategy r) {
            item.put("description", r.getDescription());
        }
        return item;
    }
}
