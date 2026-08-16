package com.jingshanghui.pos.payment.domain;

import com.jingshanghui.pos.payment.domain.PaymentStates.DifferenceType;
import com.jingshanghui.pos.payment.domain.PaymentStates.ReconciliationStatus;

import java.util.ArrayList;
import java.util.List;

/** 内部资金事实与 Provider 账单双源比较及案例生命周期规则。 */
public final class ReconciliationRules {

    private ReconciliationRules() {
    }

    public static List<DifferenceType> compare(Fact internal, Fact statement) {
        if (internal == null) return List.of(DifferenceType.PROVIDER_ONLY);
        if (statement == null) return List.of(DifferenceType.INTERNAL_ONLY);
        List<DifferenceType> differences = new ArrayList<>();
        if (internal.amountMinor() != statement.amountMinor()) differences.add(DifferenceType.AMOUNT_MISMATCH);
        if (!internal.currency().equals(statement.currency())) differences.add(DifferenceType.CURRENCY_MISMATCH);
        if (!internal.status().equals(statement.status())) differences.add(DifferenceType.STATUS_MISMATCH);
        if (!internal.businessType().equals(statement.businessType())) differences.add(DifferenceType.REFUND_MISMATCH);
        return List.copyOf(differences);
    }

    public static void requireTransition(ReconciliationStatus from, ReconciliationStatus to) {
        boolean legal = switch (from) {
            case OPEN -> to == ReconciliationStatus.INVESTIGATING || to == ReconciliationStatus.WAITING_PROVIDER;
            case INVESTIGATING, WAITING_PROVIDER -> to == ReconciliationStatus.RESOLVED;
            case RESOLVED -> to == ReconciliationStatus.APPROVED;
            case APPROVED -> to == ReconciliationStatus.CLOSED;
            case CLOSED -> false;
        };
        if (!legal) {
            throw new IllegalStateException("REC-STATE-001: 非法对账案例迁移");
        }
    }

    /** 对账参与比较的最小不可变资金事实。 */
    public record Fact(String reference, String businessType, String status, long amountMinor, String currency) {
    }
}
