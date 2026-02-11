package com.kiro.gateway.exception;

/**
 * 无可用账号异常
 */
public class NoAvailableAccountException extends KiroGatewayException {

    public NoAvailableAccountException() {
        super("没有可用的账号", 503);
    }

    public NoAvailableAccountException(String message) {
        super(message, 503);
    }
}
