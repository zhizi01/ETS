package com.github.zhizi01.ets.domain.dto;

import com.github.zhizi01.ets.domain.enums.GenType;
import com.github.zhizi01.ets.domain.enums.LoadProfile;
import com.github.zhizi01.ets.domain.enums.MemberType;
import com.github.zhizi01.ets.domain.enums.RobotStrategy;
import com.github.zhizi01.ets.domain.model.RobotConfig;
import lombok.Data;

/** POST /api/admin/robots */
@Data
public class RobotCreateReq {

    private String simId;
    private String name;
    private MemberType memberType;
    private RobotStrategy strategy;
    /** 不填则按主体类型取默认区间 */
    private Double initialCapital;
    /** GENCO 可指定机组类型，决定变动成本 */
    private GenType genType;
    /** CONSUMER 可指定负荷特性，决定现货 24 点曲线形状 */
    private LoadProfile loadProfile;
    /** 不填则用策略默认参数 */
    private RobotConfig cfg;
    private Boolean enabled;
}
