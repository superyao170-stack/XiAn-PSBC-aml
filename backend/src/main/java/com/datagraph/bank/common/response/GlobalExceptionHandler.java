package com.datagraph.bank.common.response;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DataIntegrityViolationException.class)
    public CommonResult<Void> handleConflict(DataIntegrityViolationException exception) {
        log.warn("Database integrity conflict", exception);
        return CommonResult.error(409, "数据冲突或不满足完整性约束");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public CommonResult<Void> handleBadJson(HttpMessageNotReadableException exception) {
        return CommonResult.error(400, "请求体格式错误");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public CommonResult<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        return CommonResult.error(405, "请求方法不支持");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public CommonResult<Void> handleIllegalArgument(IllegalArgumentException exception) {
        return CommonResult.error(400, exception.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    public CommonResult<Void> handleForbidden(SecurityException exception) {
        return CommonResult.error(403, exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public CommonResult<Void> handleInvalidState(IllegalStateException exception) {
        return CommonResult.error(409, exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public CommonResult<Void> handleUnexpected(Exception exception) {
        log.error("Unexpected server error", exception);
        return CommonResult.error(500, "服务器处理请求失败");
    }
}
