package com.kiro.gateway.pool;

import java.util.List;

/**
 * 账号选择策略接口
 */
public interface SelectionStrategy {

    /**
     * 从可用账号列表中选择一个
     *
     * @param available 可用账号列表（已过滤冷却和非 active）
     * @return 选中的账号，列表为空时返回 null
     */
    Account select(List<Account> available);
}
