package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.model.SimConfig;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 创建模拟盘，对应 POST /api/admin/sims。 */
@Data
public class SimCreateReq {

    @NotBlank(message = "模拟盘名称不能为空")
    @Size(max = 60, message = "模拟盘名称过长")
    private String name;

    @Size(max = 500, message = "描述过长")
    private String description;

    /** 仿真交割起始日 yyyy-MM-dd，留空取当天 */
    private String baseDate;

    /** 保留字段，当前一律按 1:1 实时推进 */
    private Double clockSpeed;

    /** 留空则使用 SimConfig 的默认规则 */
    private SimConfig cfg;

    /** 建盘后是否立即生成标准场次集合，管理端「快速开演」用 */
    private boolean generateStandardSessions;
}
