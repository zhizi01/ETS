package com.github.zhizi01.ets.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public R<Void> handleBiz(BizException e, HttpServletRequest req) {
        log.warn("业务异常 {} {} -> [{}] {}", req.getMethod(), req.getRequestURI(), e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public R<Void> handleValid(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + defaultMsg(f))
                .collect(Collectors.joining("; "));
        return R.fail(ErrorCode.PARAM_INVALID, msg.isEmpty() ? ErrorCode.PARAM_INVALID.getMsg() : msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public R<Void> handleConstraint(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; "));
        return R.fail(ErrorCode.PARAM_INVALID, msg);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public R<Void> handleBadRequest(Exception e) {
        return R.fail(ErrorCode.PARAM_INVALID, "请求参数格式有误: " + e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public R<Void> handleIllegalArgument(IllegalArgumentException e) {
        return R.fail(ErrorCode.PARAM_INVALID, e.getMessage());
    }

    /**
     * 路径不存在。默认会落到 handleOther 变成 500 并打一整页堆栈，
     * 对前端调错路径这种事来说噪音太大，这里收敛成干净的 404。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public R<Void> handleNoResource(NoResourceFoundException e, HttpServletRequest req) {
        log.warn("接口不存在 {} {}", req.getMethod(), req.getRequestURI());
        return R.fail(ErrorCode.NOT_FOUND, "接口不存在: " + req.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleOther(Exception e, HttpServletRequest req) {
        log.error("未捕获异常 {} {}", req.getMethod(), req.getRequestURI(), e);
        return R.fail(ErrorCode.SERVER_ERROR, "服务器内部错误: " + e.getClass().getSimpleName());
    }

    private String defaultMsg(FieldError f) {
        return f.getDefaultMessage() == null ? "不合法" : f.getDefaultMessage();
    }
}
