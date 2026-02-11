package com.kiro.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KiroGatewayApplication {

    private static final Logger log = LoggerFactory.getLogger(KiroGatewayApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(KiroGatewayApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("╔═══════════════════════════════════════════════════╗");
        log.info("║           Kiro Gateway Java v1.0.0                ║");
        log.info("║     OpenAI & Anthropic Compatible Gateway         ║");
        log.info("╚═══════════════════════════════════════════════════╝");
        log.info("管理面板: http://localhost:8080/admin");
        log.info("API 端点:");
        log.info("  POST /v1/chat/completions  (OpenAI)");
        log.info("  POST /v1/messages          (Anthropic)");
        log.info("  GET  /v1/models");
        log.info("  GET  /health");
    }
}
