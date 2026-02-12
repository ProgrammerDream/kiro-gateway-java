package com.kiro.gateway.controller;

import com.kiro.gateway.config.AppProperties;
import com.kiro.gateway.dao.ApiKeyDAO;
import com.kiro.gateway.trace.TraceContext;
import com.kiro.gateway.trace.TraceFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * API Key 验证过滤器
 * <p>
 * 对 /v1/ 开头的 API 请求验证 Authorization 头中的 Bearer token
 */
@Component
@Order(10)
public class ApiKeyFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final AppProperties properties;
    private final ApiKeyDAO apiKeyDAO;

    public ApiKeyFilter(AppProperties properties, ApiKeyDAO apiKeyDAO) {
        this.properties = properties;
        this.apiKeyDAO = apiKeyDAO;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // 仅对 API 路径验证
        if (!path.startsWith("/v1/")) {
            return chain.filter(exchange);
        }

        // 不要求 API Key 时跳过
        if (!properties.isRequireApiKey()) {
            return chain.filter(exchange);
        }

        // models 端点允许无 key 访问
        if (path.equals("/v1/models")) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "缺少 Authorization 头");
        }

        String apiKey = authHeader.substring(7).trim();
        if (apiKey.isEmpty()) {
            return unauthorized(exchange, "API Key 为空");
        }

        // 验证 API Key
        if (!apiKeyDAO.validate(apiKey)) {
            log.warn("无效的 API Key: {}***", apiKey.substring(0, Math.min(8, apiKey.length())));
            return unauthorized(exchange, "无效的 API Key");
        }

        // 记录到 TraceContext
        TraceContext traceCtx = TraceFilter.getTraceContext(exchange);
        if (traceCtx != null) {
            traceCtx.setApiKey(apiKey.substring(0, Math.min(8, apiKey.length())) + "***");
        }

        return chain.filter(exchange);
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"" + message + "\"}}";
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body.getBytes()))
        );
    }
}
