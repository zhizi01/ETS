package com.github.zhizi01.ets.service.sim;

import com.github.zhizi01.ets.common.BizException;
import com.github.zhizi01.ets.common.ErrorCode;
import com.github.zhizi01.ets.domain.dto.SessCreateReq;
import com.github.zhizi01.ets.domain.enums.PeriodType;
import com.github.zhizi01.ets.domain.enums.TradeMode;
import com.github.zhizi01.ets.domain.enums.Variety;
import com.github.zhizi01.ets.domain.model.TradingSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 标准场次集合。
 * <p>演练要在一屏之内展示中长期到现货的完整交易品种矩阵，
 * 逐个手建既慢又容易漏，所以把「一套完整市场」固化成预设：
 * 年度 / 月度 / 月内多日 / 日前现货 × 四种交易方式，分时段再补峰谷两场。
 * <p>时间轴是相对当前时刻排的，真正开盘时 {@link TradingSessionService#rebaseClock}
 * 会整体平移，保证无论何时开盘，场次都按同样的节奏依次开闭市。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionPresetFactory {

    public static final String STANDARD = "STANDARD";

    private final TradingSessionService sessionService;

    /**
     * 预设条目。openAfter 为相对开盘的开市延迟（分钟），duration 为持续时长（分钟）。
     * 集中出清类场次的 duration 即申报窗口，窗口结束立即出清并闭市。
     */
    private record Spec(Variety variety, PeriodType period, TradeMode mode,
                        int openAfterMinutes, int durationMinutes) {
    }

    private static final List<Spec> STANDARD_SPECS = List.of(
            // 年度：中长期基础盘，三种交易方式并行，窗口最长
            new Spec(Variety.ANNUAL, PeriodType.ALL, TradeMode.ROLLING_MATCH, 1, 240),
            new Spec(Variety.ANNUAL, PeriodType.ALL, TradeMode.LISTING, 1, 240),
            new Spec(Variety.ANNUAL, PeriodType.ALL, TradeMode.BILATERAL, 1, 240),

            // 月度：交易最活跃的品种，四种方式全覆盖
            new Spec(Variety.MONTHLY, PeriodType.ALL, TradeMode.ROLLING_MATCH, 1, 180),
            new Spec(Variety.MONTHLY, PeriodType.ALL, TradeMode.CALL_AUCTION, 2, 30),
            new Spec(Variety.MONTHLY, PeriodType.ALL, TradeMode.LISTING, 1, 180),
            new Spec(Variety.MONTHLY, PeriodType.ALL, TradeMode.BILATERAL, 1, 180),

            // 月度分时段：峰谷价差是电力市场最直观的教学点
            new Spec(Variety.MONTHLY, PeriodType.PEAK, TradeMode.ROLLING_MATCH, 1, 180),
            new Spec(Variety.MONTHLY, PeriodType.VALLEY, TradeMode.ROLLING_MATCH, 1, 180),

            // 月内多日：临近交割的调整型交易
            new Spec(Variety.MULTI_DAY, PeriodType.ALL, TradeMode.ROLLING_MATCH, 1, 120),
            new Spec(Variety.MULTI_DAY, PeriodType.ALL, TradeMode.LISTING, 1, 120),

            // 日前现货：24 点集中出清，演练的压轴环节
            new Spec(Variety.SPOT_DA, PeriodType.ALL, TradeMode.SPOT_CLEARING, 3, 30));

    public List<TradingSession> generate(String simId, String preset, boolean replaceExisting) {
        if (preset != null && !preset.isBlank() && !STANDARD.equalsIgnoreCase(preset)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "暂不支持的场次预设方案：" + preset);
        }
        if (replaceExisting) {
            int removed = sessionService.deleteAll(simId);
            if (removed > 0) {
                log.info("模拟盘 {} 已清理 {} 个旧场次", simId, removed);
            }
        }
        // 所有场次共用一个时间原点。逐个创建要往 Redis 写十几次，
        // 若各自取 now 排期，整套时间轴会被写入耗时抹掉几秒的相对关系。
        long t0 = System.currentTimeMillis();
        List<TradingSession> created = new ArrayList<>(STANDARD_SPECS.size());
        for (Spec spec : STANDARD_SPECS) {
            SessCreateReq req = new SessCreateReq();
            req.setSimId(simId);
            req.setVariety(spec.variety());
            req.setPeriodType(spec.period());
            req.setTradeMode(spec.mode());
            req.setOpenTime(t0 + spec.openAfterMinutes() * 60_000L);
            req.setCloseTime(t0 + (spec.openAfterMinutes() + spec.durationMinutes()) * 60_000L);
            created.add(sessionService.create(req));
        }
        log.info("模拟盘 {} 生成标准场次集合共 {} 个", simId, created.size());
        return created;
    }

    public List<TradingSession> generateStandard(String simId) {
        return generate(simId, STANDARD, false);
    }

    public int specCount() {
        return STANDARD_SPECS.size();
    }
}
