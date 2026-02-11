package com.kiro.gateway.auth;

import com.alibaba.fastjson2.JSONObject;
import com.kiro.gateway.config.AppProperties;
import com.kiro.gateway.exception.AuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 统一认证服务
 * <p>
 * 管理每个账号的 access token 生命周期：
 * - 自动刷新（过期前 10 分钟）
 * - 防抖（并发刷新时只执行一次）
 * - 缓存（避免重复刷新）
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    // 提前 10 分钟刷新
    private static final long REFRESH_THRESHOLD_SECONDS = 600;

    private final AppProperties properties;
    private final HttpClient httpClient;

    // accountId -> 缓存的 token 信息
    private final ConcurrentHashMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();
    // accountId -> 刷新锁（防抖）
    private final ConcurrentHashMap<String, ReentrantLock> refreshLocks = new ConcurrentHashMap<>();

    public AuthService(AppProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 获取有效的 access token
     * <p>
     * 如果 token 未过期直接返回缓存，否则自动刷新
     *
     * @param accountId   账号 ID
     * @param credentials 账号凭证（JSON 格式）
     * @param authMethod  认证方式（social / idc）
     * @return 有效的 access token
     */
    public String getAccessToken(String accountId, String credentials, String authMethod) {
        CachedToken cached = tokenCache.get(accountId);

        // 缓存有效且未到刷新阈值
        if (cached != null && !cached.needsRefresh()) {
            return cached.accessToken;
        }

        // 获取刷新锁（防抖）
        ReentrantLock lock = refreshLocks.computeIfAbsent(accountId, k -> new ReentrantLock());
        lock.lock();
        try {
            // 双重检查：其他线程可能已刷新
            cached = tokenCache.get(accountId);
            if (cached != null && !cached.needsRefresh()) {
                return cached.accessToken;
            }

            // 执行刷新
            return doRefresh(accountId, credentials, authMethod);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 强制刷新指定账号的 token
     */
    public String forceRefresh(String accountId, String credentials, String authMethod) {
        ReentrantLock lock = refreshLocks.computeIfAbsent(accountId, k -> new ReentrantLock());
        lock.lock();
        try {
            return doRefresh(accountId, credentials, authMethod);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 清除指定账号的缓存
     */
    public void clearCache(String accountId) {
        tokenCache.remove(accountId);
    }

    /**
     * 检查 token 是否即将过期
     */
    public boolean isTokenExpiringSoon(String accountId) {
        CachedToken cached = tokenCache.get(accountId);
        return cached == null || cached.needsRefresh();
    }

    private String doRefresh(String accountId, String credentials, String authMethod) {
        JSONObject creds = JSONObject.parseObject(credentials);
        String refreshToken = creds.getString("refreshToken");
        if (refreshToken == null || refreshToken.isEmpty()) {
            throw new AuthenticationException("账号 " + accountId + " 缺少 refreshToken");
        }

        String region = creds.getString("region");
        if (region == null || region.isEmpty()) {
            region = properties.getRegion();
        }

        TokenRefresher refresher = createRefresher(authMethod, creds);
        TokenRefresher.TokenResult result = refresher.refresh(refreshToken, region);

        // 更新缓存
        CachedToken cached = new CachedToken(
                result.accessToken(),
                Instant.now().plusSeconds(result.expiresInSeconds())
        );
        tokenCache.put(accountId, cached);

        // 如果 refreshToken 更新了，需要通知外部更新存储
        if (!refreshToken.equals(result.refreshToken())) {
            log.info("账号 {} 的 refreshToken 已更新", accountId);
            creds.put("refreshToken", result.refreshToken());
            // TODO: 持久化新的 refreshToken 到数据库
        }

        log.info("账号 {} Token 刷新成功, 过期时间: {}", accountId, cached.expiresAt);
        return result.accessToken();
    }

    private TokenRefresher createRefresher(String authMethod, JSONObject creds) {
        return switch (authMethod.toLowerCase()) {
            case "idc", "builderid" -> {
                // clientId/clientSecret 可选，缺少时 OidcTokenRefresher 会自动注册
                String clientId = creds.getString("clientId");
                String clientSecret = creds.getString("clientSecret");
                yield new OidcTokenRefresher(clientId, clientSecret, httpClient);
            }
            case "social" -> new SocialTokenRefresher(httpClient);
            default -> throw new AuthenticationException("不支持的认证方式: " + authMethod);
        };
    }

    // 缓存的 Token 信息
    private static class CachedToken {
        final String accessToken;
        final Instant expiresAt;

        CachedToken(String accessToken, Instant expiresAt) {
            this.accessToken = accessToken;
            this.expiresAt = expiresAt;
        }

        boolean needsRefresh() {
            return Instant.now().plusSeconds(REFRESH_THRESHOLD_SECONDS).isAfter(expiresAt);
        }
    }
}
