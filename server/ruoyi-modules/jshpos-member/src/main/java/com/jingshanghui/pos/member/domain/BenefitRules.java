package com.jingshanghui.pos.member.domain;

import com.jingshanghui.pos.member.application.model.BenefitCommands.LevelRule;
import org.dromara.common.core.exception.ServiceException;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** T2-MEM-003 权益版本不变量；不包含身份明文或价格计算。 */
public final class BenefitRules {
    public static final int MAX_LEVEL_RULES = 100;
    public static final int MAX_STORE_SCOPES = 1000;
    public static final long OFFLINE_TTL_HOURS = 24L;

    public static List<LevelRule> requireLevelRules(List<LevelRule> rules) {
        if (rules == null || rules.isEmpty() || rules.size() > MAX_LEVEL_RULES) {
            throw new ServiceException("MEM-BENEFIT-001: 等级权益映射数量无效", 400);
        }
        Set<String> codes = new HashSet<>();
        for (LevelRule rule : rules) {
            if (rule == null || rule.levelCode() == null || !rule.levelCode().matches("^[A-Z0-9_-]{1,32}$")) {
                throw new ServiceException("MEM-BENEFIT-002: 等级编码无效", 400);
            }
            if (!codes.add(rule.levelCode())) {
                throw new ServiceException("MEM-BENEFIT-003: 等级权益映射重复", 409);
            }
        }
        return List.copyOf(rules);
    }

    public static List<Long> requireStoreIds(List<Long> storeIds) {
        if (storeIds == null || storeIds.isEmpty() || storeIds.size() > MAX_STORE_SCOPES) {
            throw new ServiceException("MEM-BENEFIT-004: 门店适用范围无效", 400);
        }
        Set<Long> unique = new HashSet<>();
        for (Long storeId : storeIds) {
            if (storeId == null || storeId <= 0 || !unique.add(storeId)) {
                throw new ServiceException("MEM-BENEFIT-005: 门店适用范围含无效或重复值", 409);
            }
        }
        return List.copyOf(storeIds);
    }

    public static void requireWindow(LocalDateTime effectiveAt, LocalDateTime expiresAt) {
        if (effectiveAt == null || (expiresAt != null && !expiresAt.isAfter(effectiveAt))) {
            throw new ServiceException("MEM-BENEFIT-006: 权益生效窗口无效", 400);
        }
    }

    private BenefitRules() { }
}
