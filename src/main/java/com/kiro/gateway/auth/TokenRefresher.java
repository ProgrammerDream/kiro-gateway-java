package com.kiro.gateway.auth;

/**
 * Token 刷新接口
 * <p>
 * 不同认证方式实现不同的刷新逻辑
 */
public interface TokenRefresher {

    /**
     * 刷新 access token
     *
     * @param refreshToken 刷新令牌
     * @param region       AWS 区域
     * @return 刷新结果
     */
    TokenResult refresh(String refreshToken, String region);

    /**
     * 刷新结果
     *
     * @param accessToken  新的访问令牌
     * @param refreshToken 新的刷新令牌（部分方式会更新）
     * @param expiresInSeconds 过期时间（秒）
     */
    record TokenResult(String accessToken, String refreshToken, long expiresInSeconds) {}
}
