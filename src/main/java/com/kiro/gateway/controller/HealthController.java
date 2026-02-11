package com.kiro.gateway.controller;

import com.alibaba.fastjson2.JSONObject;
import com.kiro.gateway.pool.AccountPool;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 健康检查端点
 */
@RestController
public class HealthController {

    private final AccountPool accountPool;

    public HealthController(AccountPool accountPool) {
        this.accountPool = accountPool;
    }

    @GetMapping(value = "/health", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<String> health() {
        AccountPool.PoolStats stats = accountPool.getStats();
        JSONObject result = new JSONObject();
        result.put("status", stats.active() > 0 ? "ok" : "degraded");
        result.put("version", "1.0.0");
        result.put("accounts", JSONObject.of( //
                "total", stats.total(), //
                "active", stats.active(), //
                "cooldown", stats.cooldown() //
        ));
        result.put("totalRequests", stats.totalRequests());
        return Mono.just(result.toJSONString());
    }
}
