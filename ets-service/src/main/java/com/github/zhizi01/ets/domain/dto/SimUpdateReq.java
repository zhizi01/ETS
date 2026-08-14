package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.model.SimConfig;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 修改模拟盘。字段为 null 表示不改。 */
@Data
public class SimUpdateReq {

    @Size(max = 60, message = "模拟盘名称过长")
    private String name;

    @Size(max = 500, message = "描述过长")
    private String description;

    private String baseDate;
    private Double clockSpeed;
    private SimConfig cfg;
}
