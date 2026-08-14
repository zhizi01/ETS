package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.model.RobotConfig;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 策略元数据，供管理端下拉框与「新建机器人」表单预填默认参数。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RobotStrategyVO {

    private String value;
    private String label;
    private String description;
    /** 适合该策略的主体类型，前端据此联动 */
    private List<String> suitableMemberTypes;
    private RobotConfig defaultCfg;
}
