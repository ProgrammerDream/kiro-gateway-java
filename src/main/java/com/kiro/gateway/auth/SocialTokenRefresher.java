package com.kiro.gateway.auth;

import com.alibaba.fastjson2.JSONObject;
import com.kiro.gateway.exception.AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Social Token 刷新（Kiro Desktop 登录方式）
 * <p>
 * 使用 Kiro 桌面端认证服务的 /refreshToken 端点
 */
public class SocialTokenRefresher implements TokenRefresher {

    private static final Logger log = LoggerFactory.getLogger(SocialTokenRefresher.class);

    private final HttpClient httpClient;

    public SocialTokenRefresher(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public TokenResult refresh(String refreshToken, String region) {
        String tokenUrl = "https://prod." + region + ".auth.desktop.kiro.dev/refreshToken";

        JSONObject body = JSONObject.of(
                "refreshToken", refreshToken //
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Social Token 刷新失败: status={}, body={}", response.statusCode(), response.body());
                throw new AuthenticationException("Social Token 刷新失败: " + response.statusCode());
            }

            JSONObject json = JSONObject.parseObject(response.body());
            String accessToken = json.getString("accessToken");
            String newRefreshToken = json.getString("refreshToken");
            long expiresIn = json.getLongValue("expiresIn", 3600);

            if (accessToken == null || accessToken.isEmpty()) {
                throw new AuthenticationException("Social 刷新未返回 accessToken");
            }

            log.debug("Social Token 刷新成功: expiresIn={}s", expiresIn);
            return new TokenResult(accessToken, newRefreshToken != null ? newRefreshToken : refreshToken, expiresIn);

        } catch (AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthenticationException("Social Token 刷新异常: " + e.getMessage(), e);
        }
    }
}
