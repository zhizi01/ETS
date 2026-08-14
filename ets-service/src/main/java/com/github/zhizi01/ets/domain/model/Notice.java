package com.github.zhizi01.ets.domain.model;

import com.github.zhizi01.ets.domain.enums.NoticeLevel;
import lombok.Data;

/** 市场公告。 */
@Data
public class Notice {

    private String id;
    private String simId;
    private String title;
    private String content;
    private NoticeLevel level = NoticeLevel.INFO;
    private String createdByAdminId;
    private long createdAt;
}
