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
 * OIDC Token 刷新（IdC / Builder ID）
 * <p>
 * 使用 AWS SSO OIDC 的 /token 端点刷新 access token
 * 需要 clientId 和 clientSecret
 */
public class OidcTokenRefresher implements TokenRefresher {

    private static final Logger log = LoggerFactory.getLogger(OidcTokenRefresher.class);

    private final String clientId;
    private final String clientSecret;
    private final HttpClient httpClient;

    public OidcTokenRefresher(String clientId, String clientSecret, HttpClient httpClient) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.httpClient = httpClient;
    }

    @Override
    public TokenResult refresh(String refreshToken, String region) {
        String tokenUrl = "https://oidc." + region + ".amazonaws.com/token";

        JSONObject body = JSONObject.of(
                "grant_type", "refresh_token", //
                "client_id", clientId, //
                "client_secret", clientSecret, //
                "refresh_token", refreshToken //
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("OIDC Token 刷新失败: status={}, body={}", response.statusCode(), response.body());
                throw new AuthenticationException("OIDC Token 刷新失败: " + response.statusCode());
            }

            JSONObject json = JSONObject.parseObject(response.body());
            String accessToken = json.getString("accessToken");
            String newRefreshToken = json.getString("refreshToken");
            long expiresIn = json.getLongValue("expiresIn", 3600);

            if (accessToken == null || accessToken.isEmpty()) {
                throw new AuthenticationException("OIDC 刷新未返回 accessToken");
            }

            log.debug("OIDC Token 刷新成功: expiresIn={}s", expiresIn);
            return new TokenResult(accessToken, newRefreshToken != null ? newRefreshToken : refreshToken, expiresIn);

        } catch (AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthenticationException("OIDC Token 刷新异常: " + e.getMessage(), e);
        }
    }
}
