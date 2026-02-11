package com.kiro.gateway.exception;

import lombok.Getter;

/**
 * Kiro Gateway 异常基类
 */
@Getter
public class KiroGatewayException extends RuntimeException {

    private final int statusCode;

    public KiroGatewayException(String message) {
        super(message);
        this.statusCode = 500;
    }

    public KiroGatewayException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public KiroGatewayException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 500;
    }

    public KiroGatewayException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

}
