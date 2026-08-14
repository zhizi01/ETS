package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.enums.RobotStrategy;
import com.github.zhizi01.ets.domain.model.RobotConfig;
import lombok.Data;

/** PUT /api/admin/robots/{id}。字段为 null 表示不改。 */
@Data
public class RobotUpdateReq {

    private String name;
    private RobotStrategy strategy;
    private Boolean enabled;
    private RobotConfig cfg;
}
