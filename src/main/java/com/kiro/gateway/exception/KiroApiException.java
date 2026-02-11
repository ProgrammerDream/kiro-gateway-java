package com.kiro.gateway.exception;

import lombok.Getter;

/**
 * Kiro API 调用异常
 */
@Getter
public class KiroApiException extends KiroGatewayException {

    private final String responseBody;

    public KiroApiException(int statusCode, String responseBody) {
        super("Kiro API 错误: " + statusCode + " - " + responseBody, statusCode);
        this.responseBody = responseBody;
    }

    public KiroApiException(int statusCode, String responseBody, Throwable cause) {
        super("Kiro API 错误: " + statusCode + " - " + responseBody, statusCode, cause);
        this.responseBody = responseBody;
    }

    public boolean isRateLimit() {
        return getStatusCode() == 429;
    }

    public boolean isAuthError() {
        return getStatusCode() == 401 || getStatusCode() == 403;
    }
}
