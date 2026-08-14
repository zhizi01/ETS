package com.github.zhizi01.ets.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 一键生成标准场次集合，对应 POST /api/admin/sessions/batch-generate。 */
@Data
public class SessBatchGenReq {

    @NotBlank(message = "模拟盘 id 不能为空")
    private String simId;

    /** 预设方案，当前仅 STANDARD */
    private String preset = "STANDARD";

    /** true 时先清空该盘已有场次再生成，避免重复点击造成堆积 */
    private boolean replaceExisting;
}
