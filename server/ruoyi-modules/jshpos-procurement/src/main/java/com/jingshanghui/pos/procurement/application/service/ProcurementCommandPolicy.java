package com.jingshanghui.pos.procurement.application.service;

import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ApproveReturn;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ConfirmReceipt;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.CreateOrder;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.CreateReceipt;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.CreateReturn;
import com.jingshanghui.pos.procurement.domain.ProcurementHash;
import com.jingshanghui.pos.procurement.domain.ProcurementRules;
import org.dromara.common.core.exception.ServiceException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * 采购命令的输入、幂等摘要和关联标识策略。
 *
 * <p>本策略无 Mapper、无事务、无跨 Owner 调用，只把原服务中的纯规则职责聚合到具名边界；
 * 错误码、排序和摘要输入顺序保持不变。</p>
 */
final class ProcurementCommandPolicy {

    void validateOrder(CreateOrder command) {
        ProcurementRules.ulid(command.orderId(), "orderId");
        ProcurementRules.ulid(command.supplierId(), "supplierId");
        ProcurementRules.ulid(command.warehouseId(), "warehouseId");
        requireCorrelation(command.correlationId());
        if (command.storeId() == null || command.storeId() <= 0 || command.expectedDate() == null
            || command.lines().isEmpty() || command.lines().size() > 500
            || new HashSet<>(command.lines().stream().map(
                com.jingshanghui.pos.procurement.application.model.ProcurementCommands.OrderLine::orderLineId)
                .toList()).size() != command.lines().size()) {
            throw new ServiceException("PUR-ORDER-002: 采购单主体或行数非法", 409);
        }
    }

    void validateReceiptDraft(CreateReceipt command) {
        ProcurementRules.ulid(command.receiptId(), "receiptId");
        ProcurementRules.ulid(command.orderId(), "orderId");
        requireCorrelation(command.correlationId());
        if (command.lines().isEmpty() || command.lines().size() > 500
            || new HashSet<>(command.lines().stream().map(
                com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ReceiptLine::receiptLineId)
                .toList()).size() != command.lines().size()) {
            throw new ServiceException("PUR-RECEIPT-006: 收货行必须唯一且为1至500项", 409);
        }
    }

    void validateReceiptConfirmation(ConfirmReceipt command) {
        ProcurementRules.ulid(command.receiptId(), "receiptId");
        ProcurementRules.ulid(command.eventId(), "eventId");
        requireCorrelation(command.correlationId());
        if (command.lotSplits().size() > 1000) {
            throw new ServiceException("PUR-LOT-009: 收货批次拆分超过1000项", 409);
        }
        command.lotSplits().forEach(split -> {
            ProcurementRules.ulid(split.receiptLineId(), "receiptLineId");
            ProcurementRules.quantity(split.baseQuantity(), "lotBaseQuantity");
        });
    }

    void validateReturnDraft(CreateReturn command) {
        ProcurementRules.ulid(command.purchaseReturnId(), "purchaseReturnId");
        ProcurementRules.ulid(command.receiptId(), "receiptId");
        requireCorrelation(command.correlationId());
        if (command.lines().isEmpty() || command.lines().size() > 500
            || new HashSet<>(command.lines().stream().map(
                com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ReturnLine::returnLineId)
                .toList()).size() != command.lines().size()) {
            throw new ServiceException("PUR-RETURN-006: 退货行必须唯一且为1至500项", 409);
        }
    }

    void validateReturnApproval(ApproveReturn command) {
        ProcurementRules.ulid(command.purchaseReturnId(), "purchaseReturnId");
        ProcurementRules.ulid(command.eventId(), "eventId");
        requireCorrelation(command.correlationId());
        if (command.lotSplits().size() > 1000) {
            throw new ServiceException("PUR-LOT-010: 退货批次拆分超过1000项", 409);
        }
        command.lotSplits().forEach(split -> {
            ProcurementRules.ulid(split.returnLineId(), "returnLineId");
            ProcurementRules.ulid(split.lotId(), "lotId");
            ProcurementRules.quantity(split.baseQuantity(), "lotBaseQuantity");
        });
    }

    String hashOrder(CreateOrder command) {
        List<Object> values = new ArrayList<>(List.of(command.orderId(), command.supplierId(), command.storeId(),
            command.warehouseId(), command.expectedDate(), command.overReceiptToleranceBps()));
        command.lines().stream().sorted(Comparator.comparing(
            com.jingshanghui.pos.procurement.application.model.ProcurementCommands.OrderLine::orderLineId))
            .forEach(line -> {
                values.add(line.orderLineId());
                values.add(line.skuId());
                values.add(line.unitId());
                values.add(line.orderedQuantity());
                values.add(line.unitPriceMinor());
                values.add(line.taxRateBps());
            });
        return ProcurementHash.sha256(ProcurementHash.canonical(values));
    }

    void requireCorrelation(String value) {
        ProcurementRules.text(value, 96, "PUR-INPUT-002");
    }
}
