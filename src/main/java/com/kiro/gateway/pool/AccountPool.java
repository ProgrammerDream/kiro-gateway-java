package com.kiro.gateway.pool;

import com.kiro.gateway.config.AppProperties;
import com.kiro.gateway.dao.AccountDAO;
import com.kiro.gateway.exception.NoAvailableAccountException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多账号池管理
 * <p>
 * 支持 4 种选择策略：round-robin / random / least-used / smart-score
 */
@Component
public class AccountPool {

    private static final Logger log = LoggerFactory.getLogger(AccountPool.class);

    private final AppProperties properties;
    private final AccountDAO accountDAO;
    private final Map<String, Account> accounts = new ConcurrentHashMap<>();
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    private volatile SelectionStrategy strategy;

    public AccountPool(AppProperties properties, AccountDAO accountDAO) {
        this.properties = properties;
        this.accountDAO = accountDAO;
    }

    @PostConstruct
    public void init() {
        // 设置选择策略
        setStrategy(properties.getPoolStrategy());

        // 从数据库加载账号
        List<AccountDAO.AccountRow> rows = accountDAO.findAll();
        for (AccountDAO.AccountRow row : rows) {
            Account account = new Account(
                    row.id(), row.name(), row.credentials(), row.authMethod(),
                    row.status(), row.requestCount(), row.successCount(), row.errorCount(),
                    row.consecutiveErrors(), row.inputTokensTotal(), row.outputTokensTotal(),
                    row.creditsTotal(), row.cooldownUntil(), row.lastUsedAt(), row.createdAt()
            );
            accounts.put(row.id(), account);
        }
        log.info("账号池初始化完成: {} 个账号, 策略={}", accounts.size(), properties.getPoolStrategy());
    }

    /**
     * 设置选择策略
     */
    public void setStrategy(String strategyName) {
        this.strategy = switch (strategyName.toLowerCase()) {
            case "random" -> new RandomStrategy();
            case "least-used" -> new LeastUsedStrategy();
            case "smart-score" -> new SmartScoreStrategy();
            default -> new RoundRobinStrategy();
        };
        log.info("账号池策略切换为: {}", strategyName);
    }

    /**
     * 获取下一个可用账号
     */
    public Account getNext() {
        List<Account> available = accounts.values().stream()
                .filter(Account::isAvailable)
                .toList();

        if (available.isEmpty()) {
            throw new NoAvailableAccountException();
        }

        Account selected = strategy.select(available);
        selected.setStatus("active");
        return selected;
    }

    /**
     * 添加账号
     */
    public String addAccount(String name, String credentials, String authMethod) {
        String id = UUID.randomUUID().toString();
        Account account = new Account(id, name, credentials, authMethod);
        accounts.put(id, account);
        accountDAO.insert(id, name, credentials, authMethod);
        log.info("添加账号: id={}, name={}", id, name);
        return id;
    }

    /**
     * 更新账号信息
     */
    public boolean updateAccount(String id, String name, String credentials, String authMethod) {
        Account old = accounts.get(id);
        if (old == null) return false;

        // 用新信息 + 旧统计创建替换对象
        Account updated = new Account(
                id, name, credentials, authMethod,
                old.status(), old.requestCount(), old.successCount(), old.errorCount(),
                old.consecutiveErrors(), old.inputTokensTotal(), old.outputTokensTotal(),
                old.creditsTotal(),
                old.cooldownUntil() != null ? old.cooldownUntil().toString() : null,
                old.lastUsedAt() != null ? old.lastUsedAt().toString() : null,
                old.createdAt().toString()
        );
        accounts.put(id, updated);
        accountDAO.updateInfo(id, name, credentials, authMethod);
        // 清除旧 token 缓存
        log.info("更新账号: id={}, name={}", id, name);
        return true;
    }

    /**
     * 删除账号
     */
    public boolean removeAccount(String id) {
        Account removed = accounts.remove(id);
        if (removed != null) {
            accountDAO.delete(id);
            log.info("删除账号: id={}, name={}", id, removed.name());
            return true;
        }
        return false;
    }

    /**
     * 记录成功
     */
    public void recordSuccess(String accountId, int inputTokens, int outputTokens, double credits) {
        Account account = accounts.get(accountId);
        if (account == null) {
            return;
        }
        account.recordSuccess(inputTokens, outputTokens, credits);
        persistAccountStats(account);
    }

    /**
     * 记录失败
     */
    public void recordError(String accountId, boolean isRateLimit) {
        Account account = accounts.get(accountId);
        if (account == null) {
            return;
        }
        account.recordError(isRateLimit,
                properties.getCooldown().getQuotaMinutes(),
                properties.getCooldown().getErrorMinutes(),
                properties.getCooldown().getErrorThreshold());
        persistAccountStats(account);
    }

    /**
     * 获取所有账号信息
     */
    public List<Account> listAccounts() {
        return new ArrayList<>(accounts.values());
    }

    /**
     * 根据 ID 获取账号
     */
    public Account getById(String id) {
        return accounts.get(id);
    }

    /**
     * 获取统计信息
     */
    public PoolStats getStats() {
        int total = accounts.size();
        int active = 0;
        int cooldown = 0;
        int invalid = 0;
        int disabled = 0;
        long totalRequests = 0;
        long totalErrors = 0;

        for (Account a : accounts.values()) {
            switch (a.status()) {
                case "active" -> {
                    if (a.cooldownUntil() != null && Instant.now().isBefore(a.cooldownUntil())) {
                        cooldown++;
                        break;
                    }
                    active++;
                }
                case "invalid" -> invalid++;
                case "disabled" -> disabled++;
                default -> {}
            }
            totalRequests += a.requestCount();
            totalErrors += a.errorCount();
        }

        return new PoolStats(total, active, cooldown, invalid, disabled, totalRequests, totalErrors);
    }

    public int size() {
        return accounts.size();
    }

    public int availableCount() {
        return (int) accounts.values().stream().filter(Account::isAvailable).count();
    }

    private void persistAccountStats(Account account) {
        accountDAO.updateStats(
                account.id(), account.requestCount(), account.successCount(),
                account.errorCount(), account.consecutiveErrors(),
                account.inputTokensTotal(), account.outputTokensTotal(),
                account.creditsTotal(),
                account.cooldownUntil() != null ? account.cooldownUntil().toString() : null,
                account.lastUsedAt() != null ? account.lastUsedAt().toString() : null
        );
    }

    // ==================== 策略实现 ====================

    private class RoundRobinStrategy implements SelectionStrategy {
        @Override
        public Account select(List<Account> available) {
            int idx = roundRobinIndex.getAndIncrement() % available.size();
            return available.get(idx);
        }
    }

    private static class RandomStrategy implements SelectionStrategy {
        private final Random random = new Random();

        @Override
        public Account select(List<Account> available) {
            return available.get(random.nextInt(available.size()));
        }
    }

    private static class LeastUsedStrategy implements SelectionStrategy {
        @Override
        public Account select(List<Account> available) {
            return available.stream()
                    .min(Comparator.comparingInt(Account::requestCount))
                    .orElse(available.get(0));
        }
    }

    private static class SmartScoreStrategy implements SelectionStrategy {
        @Override
        public Account select(List<Account> available) {
            return available.stream()
                    .max(Comparator.comparingDouble(Account::calculateScore))
                    .orElse(available.get(0));
        }
    }

    // ==================== 统计 Record ====================

    public record PoolStats(int total, int active, int cooldown, int invalid, int disabled,
                             long totalRequests, long totalErrors) {}
}
