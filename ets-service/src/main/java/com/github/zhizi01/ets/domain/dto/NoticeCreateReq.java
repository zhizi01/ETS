package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.enums.NoticeLevel;
import lombok.Data;

/** POST /api/admin/notices */
@Data
public class NoticeCreateReq {

    private String simId;
    private String title;
    private String content;
    private NoticeLevel level;
}
