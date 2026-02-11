package com.kiro.gateway.controller;

import com.kiro.gateway.util.Metrics;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Prometheus 指标端点
 */
@RestController
public class MetricsController {

    @GetMapping(value = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    public Mono<String> metrics() {
        return Mono.just(Metrics.instance().toPrometheusFormat());
    }
}
