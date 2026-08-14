package com.github.zhizi01.ets.common;

import lombok.Getter;

@Getter
public enum ErrorCode {

    PARAM_INVALID(400, "参数不合法"),
    UNAUTHORIZED(401, "未登录或登录已失效"),
    FORBIDDEN(403, "无权访问"),
    NOT_FOUND(404, "资源不存在"),
    SERVER_ERROR(500, "服务器内部错误"),

    USERNAME_EXISTS(1001, "用户名已存在"),
    LOGIN_FAILED(1002, "用户名或密码错误"),
    USER_FROZEN(1003, "账号已被冻结"),
    OLD_PASSWORD_WRONG(1004, "原密码错误"),

    SIM_NOT_FOUND(2001, "模拟盘不存在"),
    SIM_STATE_ILLEGAL(2002, "模拟盘当前状态不允许该操作"),
    SIM_NOT_RUNNING(2003, "模拟盘尚未开盘"),
    SESSION_NOT_FOUND(2101, "交易场次不存在"),
    SESSION_STATE_ILLEGAL(2102, "交易场次当前状态不允许该操作"),
    SESSION_CLOSED(2103, "交易场次已闭市"),

    ACCOUNT_NOT_FOUND(3001, "交易账户不存在"),
    ACCOUNT_NOT_OWNED(3002, "该交易账户不属于当前用户"),
    ACCOUNT_LOCKED(3003, "账户参数已锁定，开盘后不可修改"),
    ACCOUNT_LIMIT_EXCEEDED(3004, "创建的交易账户数量已达上限"),
    ACCOUNT_PARAM_MISSING(3005, "该主体类型的必填参数缺失"),
    ACCOUNT_CODE_EXISTS(3006, "账户编号已存在"),

    INSUFFICIENT_FUND(4001, "可用资金不足"),
    INSUFFICIENT_ENERGY(4002, "可售电量不足"),
    PRICE_OUT_OF_RANGE(4003, "申报价格超出允许范围"),
    QTY_OUT_OF_RANGE(4004, "申报电量超出允许范围"),
    POSITION_LIMIT_EXCEEDED(4005, "持仓将超出账户限额"),
    SINGLE_ORDER_LIMIT_EXCEEDED(4006, "单笔申报电量超出账户限额"),

    ORDER_NOT_FOUND(5001, "申报单不存在"),
    ORDER_NOT_CANCELABLE(5002, "该申报单当前状态不可撤销"),

    LISTING_NOT_FOUND(6001, "挂牌不存在"),
    LISTING_NOT_AVAILABLE(6002, "该挂牌已不可摘牌"),
    LISTING_SELF_TAKE(6003, "不能摘取自己的挂牌"),
    LISTING_QTY_ILLEGAL(6004, "摘牌电量不满足挂牌方要求"),

    NEGO_NOT_FOUND(7001, "协商记录不存在"),
    NEGO_NOT_YOUR_TURN(7002, "当前不是您的回应轮次"),
    NEGO_STATE_ILLEGAL(7003, "协商当前状态不允许该操作"),
    NEGO_ROUND_EXCEEDED(7004, "协商轮次已达上限"),
    NEGO_SELF(7005, "不能与自己协商"),

    SPOT_SEGMENT_ILLEGAL(8001, "现货报价曲线必须覆盖 24 个小时段"),
    SPOT_BID_NOT_FOUND(8002, "现货报价不存在"),

    ROBOT_NOT_FOUND(9001, "机器人不存在"),
    SETTLE_FAILED(9101, "结算失败"),
    CONCURRENT_BUSY(9901, "操作过于频繁，请稍后重试");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
