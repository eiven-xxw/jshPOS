package com.jingshanghui.pos.promotion.application.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.promotion.application.port.PromotionPersistencePort.PublishedRuleRow;
import com.jingshanghui.pos.promotion.domain.PromotionModels.*;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** 规则持久化投影、领域值和跨端 canonical AST 之间的唯一转换器。 */
@Component
@RequiredArgsConstructor
public class PromotionRuleDefinitionCodec {
    private final ObjectMapper objectMapper;

    /** 将 XML 只读投影还原为经过类型约束的领域规则。 */
    public RuleVersion fromRow(PublishedRuleRow row) {
        Set<Long> sku = new HashSet<>(), category = new HashSet<>(), brand = new HashSet<>(), store = new HashSet<>();
        Set<String> channels = new HashSet<>();
        Set<Integer> businessDays = new HashSet<>();
        if (row.scopeTokens() != null && !row.scopeTokens().isBlank()) {
            for (String token : row.scopeTokens().split("\\|")) {
                String[] pair = token.split(":", 2);
                if (pair.length != 2) throw corrupt("作用域令牌损坏");
                switch (pair[0]) {
                    case "SKU" -> sku.add(parseLong(pair[1]));
                    case "CATEGORY" -> category.add(parseLong(pair[1]));
                    case "BRAND" -> brand.add(parseLong(pair[1]));
                    case "STORE" -> store.add(parseLong(pair[1]));
                    case "CHANNEL" -> channels.add(pair[1]);
                    case "BUSINESS_DAY" -> businessDays.add(parseDay(pair[1]));
                    default -> throw corrupt("未知范围维度");
                }
            }
        }
        List<BundleComponent> components;
        try {
            List<Map<String, String>> raw = objectMapper.readValue(
                row.bundleComponentsJson() == null ? "[]" : row.bundleComponentsJson(), new TypeReference<>() { });
            components = raw.stream().map(item -> new BundleComponent(parseLong(item.get("skuId")),
                new BigDecimal(item.get("quantity")))).toList();
        } catch (JsonProcessingException | NumberFormatException | NullPointerException exception) {
            throw corrupt("组合组件损坏");
        }
        try {
            return new RuleVersion(row.ruleVersionId(), RuleType.valueOf(row.ruleType()), row.priority(),
                StackMode.valueOf(row.stackMode()), row.exclusiveGroup(), offset(row.effectiveFrom()),
                offset(row.effectiveTo()), new RuleScope(sku, category, brand, store, channels, businessDays),
                new RuleBenefit(row.amountMinor(), row.discountRate(), row.nthValue(), row.thresholdMinor(),
                    row.thresholdQuantity(), row.bundlePriceMinor(), components));
        } catch (IllegalArgumentException exception) {
            throw corrupt("规则枚举或时间损坏");
        }
    }

    /** 输出 Java/Dart 共同解析的完整、稳定、无租户授权含义的规则 AST。 */
    public CanonicalJson.Result canonical(RuleVersion value) {
        Map<String, Object> benefit = new TreeMap<>();
        put(benefit, "amountMinor", value.benefit().amountMinor());
        put(benefit, "discountRate", decimal(value.benefit().discountRate()));
        put(benefit, "nth", value.benefit().nth());
        put(benefit, "thresholdMinor", value.benefit().thresholdMinor());
        put(benefit, "thresholdQuantity", decimal(value.benefit().thresholdQuantity()));
        put(benefit, "bundlePriceMinor", value.benefit().bundlePriceMinor());
        if (!value.benefit().bundleComponents().isEmpty()) {
            benefit.put("bundleComponents", value.benefit().bundleComponents().stream()
                .sorted(Comparator.comparing(BundleComponent::skuId))
                .map(item -> Map.of("skuId", String.valueOf(item.skuId()),
                    "quantity", item.quantity().toPlainString())).toList());
        }
        Map<String, Object> scope = new TreeMap<>();
        putCollection(scope, "skuIds", value.scope().skuIds());
        putCollection(scope, "categoryIds", value.scope().categoryIds());
        putCollection(scope, "brandIds", value.scope().brandIds());
        putCollection(scope, "storeIds", value.scope().storeIds());
        putCollection(scope, "channels", value.scope().channels());
        if (!value.scope().businessDays().isEmpty()) {
            scope.put("businessDays", value.scope().businessDays().stream().sorted().toList());
        }
        Map<String, Object> rule = new TreeMap<>();
        rule.put("ruleVersionId", value.ruleVersionId());
        rule.put("ruleType", value.ruleType().name());
        rule.put("priority", value.priority());
        rule.put("stackMode", value.stackMode().name());
        put(rule, "exclusiveGroup", value.exclusiveGroup());
        rule.put("effectiveFrom", value.effectiveFrom().toString());
        put(rule, "effectiveTo", value.effectiveTo() == null ? null : value.effectiveTo().toString());
        rule.put("scope", scope);
        rule.put("benefit", benefit);
        return CanonicalJson.from(rule);
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        if (value != null) target.put(key, value);
    }

    private static void putCollection(Map<String, Object> target, String key, Collection<?> values) {
        if (!values.isEmpty()) target.put(key, values.stream().map(String::valueOf).sorted().toList());
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private static long parseLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException exception) {
            throw corrupt("BIGINT作用域或组件标识损坏");
        }
    }

    private static int parseDay(String value) {
        try {
            int day = Integer.parseInt(value);
            if (day < 1 || day > 7) throw new NumberFormatException();
            return day;
        } catch (NumberFormatException exception) {
            throw corrupt("业务星期损坏");
        }
    }

    private static java.time.OffsetDateTime offset(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static ServiceException corrupt(String detail) {
        return new ServiceException("PRM-RULE-006: " + detail, 500);
    }
}
