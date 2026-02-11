package com.kiro.gateway.exception;

/**
 * 认证异常（API Key 无效、Token 过期等）
 */
public class AuthenticationException extends KiroGatewayException {

    public AuthenticationException(String message) {
        super(message, 401);
    }

    public AuthenticationException(String message, Throwable cause) {
        super(message, 401, cause);
    }
}
