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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OIDC Token 刷新（IdC / Builder ID）
 * <p>
 * 使用 AWS SSO OIDC 的 /token 端点刷新 access token
 * <p>
 * clientId/clientSecret 获取优先级：
 * <p>
 * 1. 凭证中直接提供
 * <p>
 * 2. 从 Kiro IDE 本地缓存读取（~/.aws/sso/cache/{clientIdHash}.json）
 * <p>
 * 3. 自动调用 register-client 注册（新 client 无法刷新已有 token）
 */
public class OidcTokenRefresher implements TokenRefresher {

    private static final Logger log = LoggerFactory.getLogger(OidcTokenRefresher.class);

    // 按 clientIdHash 缓存已加载的 OIDC 客户端凭证
    private static final ConcurrentHashMap<String, RegisteredClient> clientCache = new ConcurrentHashMap<>();

    private String clientId;
    private String clientSecret;
    private final String clientIdHash;
    private final HttpClient httpClient;

    public OidcTokenRefresher(String clientId, String clientSecret, String clientIdHash, HttpClient httpClient) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.clientIdHash = clientIdHash;
        this.httpClient = httpClient;
    }

    @Override
    public TokenResult refresh(String refreshToken, String region) {
        // 没有 clientId/clientSecret 时，尝试从本地缓存或自动注册获取
        if (clientId == null || clientSecret == null || clientId.isEmpty() || clientSecret.isEmpty()) {
            ensureClientAvailable(region);
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
     * 确保 clientId/clientSecret 可用
     * <p>
     * 优先级：内存缓存 → 本地 Kiro IDE 缓存文件 → 自动注册新客户端
     */
    private void ensureClientAvailable(String region) {
        // 1. 内存缓存
        String cacheKey = clientIdHash != null ? clientIdHash : region;
        RegisteredClient cached = clientCache.get(cacheKey);
        if (cached != null) {
            this.clientId = cached.clientId;
            this.clientSecret = cached.clientSecret;
            return;
        }

        // 2. 从 Kiro IDE 本地缓存读取
        if (clientIdHash != null && !clientIdHash.isEmpty()) {
            if (loadFromLocalCache(clientIdHash)) {
                clientCache.put(cacheKey, new RegisteredClient(this.clientId, this.clientSecret));
                return;
            }
        }

        // 3. 自动注册新客户端（注意：新 client 无法刷新已有 token）
        log.warn("IdC 无法找到本地缓存的 clientId，尝试注册新客户端（可能导致 invalid_grant）");
        registerNewClient(region);
        clientCache.put(cacheKey, new RegisteredClient(this.clientId, this.clientSecret));
    }

    /**
     * 从 Kiro IDE 本地缓存读取 clientId/clientSecret
     * <p>
     * 路径: ~/.aws/sso/cache/{clientIdHash}.json
     */
    private boolean loadFromLocalCache(String hash) {
        Path cacheFile = Paths.get(System.getProperty("user.home"), ".aws", "sso", "cache", hash + ".json");
        if (!Files.exists(cacheFile)) {
            log.debug("本地缓存文件不存在: {}", cacheFile);
            return false;
        }

        try {
            String content = Files.readString(cacheFile);
            JSONObject json = JSONObject.parseObject(content);
            String id = json.getString("clientId");
            String secret = json.getString("clientSecret");

            if (id == null || secret == null) {
                log.warn("本地缓存文件缺少 clientId/clientSecret: {}", cacheFile);
                return false;
            }

            this.clientId = id;
            this.clientSecret = secret;
            log.info("从 Kiro IDE 本地缓存加载 OIDC 客户端: hash={}", hash);
            return true;
        } catch (Exception e) {
            log.warn("读取本地缓存文件失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 自动注册新 OIDC 客户端
     */
    private void registerNewClient(String region) {
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

            log.info("OIDC 客户端注册成功: region={}", region);
        } catch (AuthenticationException e) {
            throw e;
        } catch (Exception e) {
            throw new AuthenticationException("OIDC 客户端注册异常: " + e.getMessage(), e);
        }
    }

    private record RegisteredClient(String clientId, String clientSecret) {}
}
