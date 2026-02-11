package com.kiro.gateway.exception;

/**
 * Kiro API 调用异常
 */
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

    public String responseBody() {
        return responseBody;
    }

    public boolean isRateLimit() {
        return statusCode() == 429;
    }

    public boolean isAuthError() {
        return statusCode() == 401 || statusCode() == 403;
    }
}
