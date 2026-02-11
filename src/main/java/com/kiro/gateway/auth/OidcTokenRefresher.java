package com.kiro.gateway.auth;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.kiro.gateway.exception.AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OIDC Token 刷新（IdC / Builder ID）
 * <p>
 * 使用 AWS SSO OIDC 的 /token 端点刷新 access token
 * <p>
 * 如果未提供 clientId/clientSecret，自动调用 register-client 注册获取
 */
public class OidcTokenRefresher implements TokenRefresher {

    private static final Logger log = LoggerFactory.getLogger(OidcTokenRefresher.class);

    // 按 region 缓存已注册的 OIDC 客户端凭证
    private static final ConcurrentHashMap<String, RegisteredClient> clientCache = new ConcurrentHashMap<>();

    private String clientId;
    private String clientSecret;
    private final HttpClient httpClient;

    public OidcTokenRefresher(String clientId, String clientSecret, HttpClient httpClient) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.httpClient = httpClient;
    }

    @Override
    public TokenResult refresh(String refreshToken, String region) {
        // 没有 clientId/clientSecret 时自动注册
        if (clientId == null || clientSecret == null || clientId.isEmpty() || clientSecret.isEmpty()) {
            ensureClientRegistered(region);
        }

        String tokenUrl = "https://oidc." + region + ".amazonaws.com/token";

        JSONObject body = new JSONObject();
        body.put("clientId", clientId);
        body.put("clientSecret", clientSecret);
        body.put("grantType", "refresh_token");
        body.put("refreshToken", refreshToken);

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

    /**
     * 自动注册 OIDC 客户端
     * <p>
     * 调用 AWS SSO OIDC register-client 端点
     */
    private void ensureClientRegistered(String region) {
        RegisteredClient cached = clientCache.get(region);
        if (cached != null) {
            this.clientId = cached.clientId;
            this.clientSecret = cached.clientSecret;
            return;
        }

        log.info("IdC 自动注册 OIDC 客户端: region={}", region);
        String registerUrl = "https://oidc." + region + ".amazonaws.com/client/register";

        JSONObject body = new JSONObject();
        body.put("clientName", "Kiro");
        body.put("clientType", "public");
        body.put("scopes", new JSONArray()
                .fluentAdd("codewhisperer:completions")
                .fluentAdd("codewhisperer:analysis")
                .fluentAdd("codewhisperer:conversations")
                .fluentAdd("codewhisperer:transformations")
                .fluentAdd("codewhisperer:taskassist"));
        body.put("grantTypes", new JSONArray()
                .fluentAdd("urn:ietf:params:oauth:grant-type:device_code")
                .fluentAdd("refresh_token"));
        body.put("issuerUrl", "https://view.awsapps.com/start");

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(registerUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("OIDC 客户端注册失败: status={}, body={}", response.statusCode(), response.body());
                throw new AuthenticationException("OIDC 客户端注册失败: " + response.statusCode());
            }

            JSONObject json = JSONObject.parseObject(response.body());
            this.clientId = json.getString("clientId");
            this.clientSecret = json.getString("clientSecret");

            if (this.clientId == null || this.clientSecret == null) {
                throw new AuthenticationException("OIDC 注册未返回 clientId/clientSecret");
            }

            // 缓存
            clientCache.put(region, new RegisteredClient(this.clientId, this.clientSecret));
            log.info("OIDC 客户端注册成功: region={}", region);

        } catch (AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthenticationException("OIDC 客户端注册异常: " + e.getMessage(), e);
        }
    }

    private record RegisteredClient(String clientId, String clientSecret) {}
}
