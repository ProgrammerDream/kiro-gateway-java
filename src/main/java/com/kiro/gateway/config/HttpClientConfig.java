package com.kiro.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * HttpClient 配置
 * <p>
 * 连接池、超时、代理设置
 */
@Configuration
public class HttpClientConfig {

    private static final Logger log = LoggerFactory.getLogger(HttpClientConfig.class);

    @Bean
    public HttpClient kiroHttpClient(AppProperties properties) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_1_1);

        // 代理配置
        if (properties.getProxy().isEnabled() && properties.getProxy().getUrl() != null && !properties.getProxy().getUrl().isEmpty()) {
            String proxyUrl = properties.getProxy().getUrl();
            try {
                java.net.URI uri = java.net.URI.create(proxyUrl);
                String host = uri.getHost();
                int port = uri.getPort() > 0 ? uri.getPort() : 8080;
                builder.proxy(ProxySelector.of(new InetSocketAddress(host, port)));
                log.info("HTTP 代理已配置: {}:{}", host, port);
            } catch (Exception e) {
                log.warn("代理 URL 解析失败: {}, 将不使用代理", proxyUrl);
            }
        }

        return builder.build();
    }
}
