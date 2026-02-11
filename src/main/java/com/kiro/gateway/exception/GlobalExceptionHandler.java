package com.kiro.gateway.exception;

import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<String> handleAuth(AuthenticationException e) {
        log.warn("认证失败: {}", e.getMessage());
        return buildErrorResponse(e.statusCode(), "authentication_error", e.getMessage());
    }

    @ExceptionHandler(NoAvailableAccountException.class)
    public ResponseEntity<String> handleNoAccount(NoAvailableAccountException e) {
        log.warn("无可用账号: {}", e.getMessage());
        return buildErrorResponse(e.statusCode(), "overloaded_error", e.getMessage());
    }

    @ExceptionHandler(KiroApiException.class)
    public ResponseEntity<String> handleKiroApi(KiroApiException e) {
        log.error("Kiro API 异常: status={}, body={}", e.statusCode(), e.responseBody());
        return buildErrorResponse(e.statusCode(), "api_error", e.getMessage());
    }

    @ExceptionHandler(KiroGatewayException.class)
    public ResponseEntity<String> handleGateway(KiroGatewayException e) {
        log.error("网关异常: {}", e.getMessage(), e);
        return buildErrorResponse(e.statusCode(), "gateway_error", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleUnexpected(Exception e) {
        log.error("未预期异常: {}", e.getMessage(), e);
        return buildErrorResponse(500, "internal_error", "服务器内部错误");
    }

    private ResponseEntity<String> buildErrorResponse(int statusCode, String errorType, String message) {
        JSONObject body = JSONObject.of(
                "type", "error", //
                "error", JSONObject.of( //
                        "type", errorType, //
                        "message", message //
                ) //
        );
        return ResponseEntity
                .status(HttpStatus.valueOf(Math.min(statusCode, 599)))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toJSONString());
    }
}
