package com.github.zhizi01.ets.common;

import lombok.Data;

/**
 * 统一响应体。code=0 表示成功。
 */
@Data
public class R<T> {

    private int code;
    private String msg;
    private T data;
    private long ts = System.currentTimeMillis();

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = 0;
        r.msg = "ok";
        r.data = data;
        return r;
    }

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> fail(int code, String msg) {
        R<T> r = new R<>();
        r.code = code;
        r.msg = msg;
        return r;
    }

    public static <T> R<T> fail(ErrorCode ec) {
        return fail(ec.getCode(), ec.getMsg());
    }

    public static <T> R<T> fail(ErrorCode ec, String msg) {
        return fail(ec.getCode(), msg);
    }
}
