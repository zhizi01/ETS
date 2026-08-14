package com.github.zhizi01.ets.common;

import lombok.Getter;

@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ErrorCode ec) {
        super(ec.getMsg());
        this.code = ec.getCode();
    }

    public BizException(ErrorCode ec, String msg) {
        super(msg);
        this.code = ec.getCode();
    }

    public BizException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public static BizException of(ErrorCode ec) {
        return new BizException(ec);
    }

    public static BizException of(ErrorCode ec, String msg) {
        return new BizException(ec, msg);
    }

    public static void check(boolean condition, ErrorCode ec) {
        if (!condition) {
            throw new BizException(ec);
        }
    }

    public static void check(boolean condition, ErrorCode ec, String msg) {
        if (!condition) {
            throw new BizException(ec, msg);
        }
    }
}
