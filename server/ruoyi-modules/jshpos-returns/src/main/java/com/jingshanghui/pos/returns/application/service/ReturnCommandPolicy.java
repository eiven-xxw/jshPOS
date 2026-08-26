package com.jingshanghui.pos.returns.application.service;

import com.jingshanghui.pos.promotion.application.port.ReturnPromotionAllocationPort.AllocatedLine;
import com.jingshanghui.pos.promotion.application.port.ReturnPromotionAllocationPort.AllocationResult;
import com.jingshanghui.pos.returns.application.model.ReturnCommands.ApproveReturn;
import com.jingshanghui.pos.returns.application.model.ReturnCommands.PaymentObservation;
import com.jingshanghui.pos.returns.domain.ReturnHash;
import com.jingshanghui.pos.returns.domain.ReturnRules;
import org.dromara.common.core.exception.ServiceException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 退货审批、支付观察与促销分摊摘要策略。
 *
 * <p>该策略无 Mapper、无事务、无跨 Owner 调用；原错误码、摘要字段顺序和 UTC 转换保持不变。</p>
 */
final class ReturnCommandPolicy {
    void validateApproval(ApproveReturn command) {
        ReturnRules.requireUlid(command.commandId(), "commandId");
        ReturnRules.requireUlid(command.returnId(), "returnId");
        ReturnRules.requireUlid(command.correlationId(), "correlationId");
        if (command.reasonCode() == null || !command.reasonCode().matches("^[A-Z0-9_]{2,32}$")
            || command.occurredAt() == null) {
            throw new ServiceException("RET-APPROVE-001: 审批字段非法", 409);
        }
    }

    void validateObservation(PaymentObservation observation) {
        ReturnRules.requireUlid(observation.observationId(), "observationId");
        ReturnRules.requireUlid(observation.returnId(), "returnId");
        ReturnRules.requireHash(observation.payloadSha256(), "payloadSha256");
        if (observation.amountMinor() < 0 || observation.observedAt() == null
            || observation.paymentStatus() == null) {
            throw new ServiceException("RET-PAY-005: Payment观察字段非法", 409);
        }
    }

    String hashAllocation(AllocationResult result) {
        List<Object> values = new ArrayList<>(List.of(result.refundId(), result.snapshotId(),
            result.grossAmountMinor(), result.recoveredDiscountMinor(), result.refundableAmountMinor()));
        result.lines().stream().sorted(Comparator.comparing(AllocatedLine::lineId)).forEach(line -> {
            values.add(line.lineId()); values.add(line.quantity().toPlainString()); values.add(line.grossAmountMinor());
            values.add(line.recoveredDiscountMinor()); values.add(line.refundableAmountMinor());
            values.add(line.cumulativeQuantity().toPlainString()); values.add(line.cumulativePayableAmountMinor());
        });
        return ReturnHash.sha256(ReturnHash.canonical(values));
    }

    LocalDateTime utc(Instant value) {
        if (value == null) throw new ServiceException("RET-TIME-001: 发生时间必填", 409);
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
