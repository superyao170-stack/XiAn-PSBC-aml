
package com.datagraph.bank.common.response;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CommonResult<T> {

    private int code;
    private String message;
    private T data;
    private String timestamp;

    private CommonResult(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = LocalDateTime.now().toString();
    }

    public static <T> CommonResult<T> success(T data) {
        return new CommonResult<>(200, "success", data);
    }

    public static <T> CommonResult<T> success(String message, T data) {
        return new CommonResult<>(200, message, data);
    }

    public static <T> CommonResult<T> error(int code, String message) {
        return new CommonResult<>(code, message, null);
    }

    public static <T> CommonResult<T> error(String message) {
        return new CommonResult<>(500, message, null);
    }

    public static <T> CommonResult<T> unauthorized() {
        return new CommonResult<>(401, "Unauthorized", null);
    }

    public static <T> CommonResult<T> forbidden() {
        return new CommonResult<>(403, "Forbidden", null);
    }

    public static <T> CommonResult<T> notFound(String message) {
        return new CommonResult<>(404, message, null);
    }

    public static <T> CommonResult<T> badRequest(String message) {
        return new CommonResult<>(400, message, null);
    }
}
