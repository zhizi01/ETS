package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.enums.MarketEventType;
import lombok.Data;

/** POST /api/admin/events */
@Data
public class EventCreateReq {

    private String simId;
    private MarketEventType type;
    /** 强度 -1 ~ 1，与事件类型的固有冲击相乘 */
    private Double magnitude;
    private Integer durationMinutes;
    private String title;
    private String description;
}
