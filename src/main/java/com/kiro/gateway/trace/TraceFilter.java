package com.kiro.gateway.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 追踪 WebFilter
 * <p>
 * 为每个 API 请求创建 TraceContext，绑定到 exchange attributes
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter implements WebFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);
    public static final String TRACE_CONTEXT_ATTR = "traceContext";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        // 仅对 API 请求创建追踪
        if (!isApiPath(path)) {
            return chain.filter(exchange);
        }

        TraceContext ctx = TraceContext.create();
        exchange.getAttributes().put(TRACE_CONTEXT_ATTR, ctx);

        log.debug("追踪开始: traceId={}, path={}", ctx.traceId(), path);

        return chain.filter(exchange)
                .doFinally(signal -> {
                    log.debug("追踪结束: traceId={}, duration={}ms, success={}",
                            ctx.traceId(), ctx.durationMs(), ctx.success());
                });
    }

    /**
     * 从 exchange 获取 TraceContext
     */
    public static TraceContext getTraceContext(ServerWebExchange exchange) {
        return exchange.getAttribute(TRACE_CONTEXT_ATTR);
    }

    private boolean isApiPath(String path) {
        return path.startsWith("/v1/") || path.startsWith("/api/");
    }
}
