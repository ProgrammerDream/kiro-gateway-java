package com.kiro.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 应用配置属性绑定
 */
@Component
@ConfigurationProperties(prefix = "kiro")
public class AppProperties {

    private String adminPassword = "admin123";
    private String apiKey = "sk-kiro-default";
    private boolean requireApiKey = true;
    private String region = "us-east-1";
    private String kiroVersion = "0.8.0";
    private String poolStrategy = "round-robin";
    private ProxyConfig proxy = new ProxyConfig();
    private CooldownConfig cooldown = new CooldownConfig();
    private ThinkingConfig thinking = new ThinkingConfig();
    private List<String> endpoints = List.of(
            "https://codewhisperer.us-east-1.amazonaws.com/generateAssistantResponse",
            "https://q.us-east-1.amazonaws.com/generateAssistantResponse"
    );
    private LoggingConfig logging = new LoggingConfig();
    private DatabaseConfig database = new DatabaseConfig();
    private RetryConfig retry = new RetryConfig();

    // --- getter / setter ---

    public String adminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }

    public String apiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean requireApiKey() {
        return requireApiKey;
    }

    public void setRequireApiKey(boolean requireApiKey) {
        this.requireApiKey = requireApiKey;
    }

    public String region() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String kiroVersion() {
        return kiroVersion;
    }

    public void setKiroVersion(String kiroVersion) {
        this.kiroVersion = kiroVersion;
    }

    public String poolStrategy() {
        return poolStrategy;
    }

    public void setPoolStrategy(String poolStrategy) {
        this.poolStrategy = poolStrategy;
    }

    public ProxyConfig proxy() {
        return proxy;
    }

    public void setProxy(ProxyConfig proxy) {
        this.proxy = proxy;
    }

    public CooldownConfig cooldown() {
        return cooldown;
    }

    public void setCooldown(CooldownConfig cooldown) {
        this.cooldown = cooldown;
    }

    public ThinkingConfig thinking() {
        return thinking;
    }

    public void setThinking(ThinkingConfig thinking) {
        this.thinking = thinking;
    }

    public List<String> endpoints() {
        return endpoints;
    }

    public void setEndpoints(List<String> endpoints) {
        this.endpoints = endpoints;
    }

    public LoggingConfig logging() {
        return logging;
    }

    public void setLogging(LoggingConfig logging) {
        this.logging = logging;
    }

    public DatabaseConfig database() {
        return database;
    }

    public void setDatabase(DatabaseConfig database) {
        this.database = database;
    }

    public RetryConfig retry() {
        return retry;
    }

    public void setRetry(RetryConfig retry) {
        this.retry = retry;
    }

    // --- 嵌套配置类 ---

    public static class ProxyConfig {
        private boolean enabled = false;
        private String url = "";

        public boolean enabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String url() { return url; }
        public void setUrl(String url) { this.url = url; }
    }

    public static class CooldownConfig {
        private int quotaMinutes = 60;
        private int errorMinutes = 1;
        private int errorThreshold = 3;

        public int quotaMinutes() { return quotaMinutes; }
        public void setQuotaMinutes(int quotaMinutes) { this.quotaMinutes = quotaMinutes; }
        public int errorMinutes() { return errorMinutes; }
        public void setErrorMinutes(int errorMinutes) { this.errorMinutes = errorMinutes; }
        public int errorThreshold() { return errorThreshold; }
        public void setErrorThreshold(int errorThreshold) { this.errorThreshold = errorThreshold; }
    }

    public static class ThinkingConfig {
        private String suffix = "-thinking";
        private String outputFormat = "xml";
        private String prompt = "<thinking_mode>enabled</thinking_mode>";

        public String suffix() { return suffix; }
        public void setSuffix(String suffix) { this.suffix = suffix; }
        public String outputFormat() { return outputFormat; }
        public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }
        public String prompt() { return prompt; }
        public void setPrompt(String prompt) { this.prompt = prompt; }
    }

    public static class LoggingConfig {
        private String filePath = "data/logs";
        private String maxFileSize = "100MB";
        private int maxHistory = 30;
        private String totalSizeCap = "1GB";
        private int requestLogRetention = 100000;
        private String requestLogCleanupCron = "0 0 3 * * ?";
        private int traceRetention = 50000;
        private String traceCleanupCron = "0 0 3 * * ?";

        public String filePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }
        public String maxFileSize() { return maxFileSize; }
        public void setMaxFileSize(String maxFileSize) { this.maxFileSize = maxFileSize; }
        public int maxHistory() { return maxHistory; }
        public void setMaxHistory(int maxHistory) { this.maxHistory = maxHistory; }
        public String totalSizeCap() { return totalSizeCap; }
        public void setTotalSizeCap(String totalSizeCap) { this.totalSizeCap = totalSizeCap; }
        public int requestLogRetention() { return requestLogRetention; }
        public void setRequestLogRetention(int requestLogRetention) { this.requestLogRetention = requestLogRetention; }
        public String requestLogCleanupCron() { return requestLogCleanupCron; }
        public void setRequestLogCleanupCron(String requestLogCleanupCron) { this.requestLogCleanupCron = requestLogCleanupCron; }
        public int traceRetention() { return traceRetention; }
        public void setTraceRetention(int traceRetention) { this.traceRetention = traceRetention; }
        public String traceCleanupCron() { return traceCleanupCron; }
        public void setTraceCleanupCron(String traceCleanupCron) { this.traceCleanupCron = traceCleanupCron; }
    }

    public static class DatabaseConfig {
        private String path = "data/kiro.db";

        public String path() { return path; }
        public void setPath(String path) { this.path = path; }
    }

    public static class RetryConfig {
        private int maxRetries = 3;
        private long baseDelayMs = 1000;

        public int maxRetries() { return maxRetries; }
        public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
        public long baseDelayMs() { return baseDelayMs; }
        public void setBaseDelayMs(long baseDelayMs) { this.baseDelayMs = baseDelayMs; }
    }
}
