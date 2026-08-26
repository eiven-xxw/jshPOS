package com.jingshanghui.pos.procurement.application.service;

import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.CommandSource;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ExplicitCommand;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ExplicitLine;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ReceiveCommand;
import com.jingshanghui.pos.inventory.application.model.LotInventoryModels.ReceiveLine;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeInventoryMovementPort.OwnedMovementLine;
import com.jingshanghui.pos.inventory.application.port.AuthoritativeLotMovementPort;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ApproveReturn;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ConfirmReceipt;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ReceiptLotSplit;
import com.jingshanghui.pos.procurement.application.model.ProcurementCommands.ReturnLotSplit;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.OrderHead;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.ReceiptHead;
import com.jingshanghui.pos.procurement.application.model.ProcurementViews.ReceiptLine;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 采购收货与退货的批次数量编排器。
 *
 * <p>只负责把采购 Owner 已冻结的行转换为库存 Owner 批次命令；事务仍由
 * {@link ProcurementService} 的公开入口控制，错误码、摘要和库存事实语义不变。</p>
 */
@Component
@RequiredArgsConstructor
final class ProcurementLotCoordinator {

    private final AuthoritativeLotMovementPort lotMovementPort;

    /** 社区超市启用 SKU 必须将收货基础数量完整拆分到批次。 */
    void applyReceiptLots(ConfirmReceipt command, ReceiptHead receipt, OrderHead order,
                          List<ReceiptLine> receiptLines, LocalDate businessDate) {
        Map<String, List<ReceiptLotSplit>> byLine = command.lotSplits().stream()
            .collect(Collectors.groupingBy(ReceiptLotSplit::receiptLineId));
        List<ReceiveLine> lotLines = new ArrayList<>();
        for (ReceiptLine line : receiptLines) {
            boolean required = lotMovementPort.requiresLotTracking(order.storeId(), line.skuId(), businessDate);
            List<ReceiptLotSplit> splits = byLine.remove(line.receiptLineId());
            if (!required) {
                if (splits != null && !splits.isEmpty()) {
                    throw new ServiceException("PUR-LOT-001: 未启用批次的商品禁止提交批次拆分", 409);
                }
                continue;
            }
            if (splits == null || splits.isEmpty()) {
                throw new ServiceException("PUR-LOT-002: 已启用批次的收货行缺少批次拆分", 409);
            }
            BigDecimal total = splits.stream().map(ReceiptLotSplit::baseQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.compareTo(line.baseQuantity()) != 0) {
                throw new ServiceException("PUR-LOT-003: 批次拆分与收货基础数量不守恒", 409);
            }
            splits.forEach(split -> lotLines.add(new ReceiveLine(split.receiptLineId(), line.skuId(),
                line.baseUnitId(), split.baseQuantity(), split.supplierLotCode(), split.internalLotCode(),
                split.productionDate(), split.receivedDate(), split.explicitExpiryDate())));
        }
        if (!byLine.isEmpty()) throw new ServiceException("PUR-LOT-004: 批次拆分引用了未知收货行", 409);
        if (!lotLines.isEmpty()) {
            lotMovementPort.receive(new ReceiveCommand(new CommandSource(command.eventId(), "PURCHASE_RECEIPT",
                receipt.receiptId(), order.warehouseId(), order.storeId(), businessDate,
                command.correlationId()), lotLines));
        }
    }

    /** 已启用批次商品的采购退货必须完整指定原仓批次。 */
    void applyReturnLots(ApproveReturn command, ReceiptHead receipt,
                         List<OwnedMovementLine> movements, LocalDate businessDate) {
        Map<String, List<ReturnLotSplit>> byLine = command.lotSplits().stream()
            .collect(Collectors.groupingBy(ReturnLotSplit::returnLineId));
        List<ExplicitLine> lotLines = new ArrayList<>();
        for (OwnedMovementLine movement : movements) {
            boolean required = lotMovementPort.requiresLotTracking(receipt.storeId(), movement.skuId(), businessDate);
            List<ReturnLotSplit> splits = byLine.remove(movement.sourceLineId());
            if (!required) {
                if (splits != null && !splits.isEmpty()) {
                    throw new ServiceException("PUR-LOT-005: 未启用批次的采购退货禁止提交批次拆分", 409);
                }
                continue;
            }
            if (splits == null || splits.isEmpty()) {
                throw new ServiceException("PUR-LOT-006: 已启用批次的采购退货缺少批次拆分", 409);
            }
            BigDecimal total = splits.stream().map(ReturnLotSplit::baseQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.compareTo(movement.quantity()) != 0) {
                throw new ServiceException("PUR-LOT-007: 退货批次拆分与仓库总账数量不守恒", 409);
            }
            splits.forEach(split -> lotLines.add(new ExplicitLine(split.returnLineId(), split.lotId(),
                movement.skuId(), movement.baseUnitId(), split.baseQuantity(),
                movement.movementType().name())));
        }
        if (!byLine.isEmpty()) throw new ServiceException("PUR-LOT-008: 批次拆分引用了未知采购退货行", 409);
        if (!lotLines.isEmpty()) {
            lotMovementPort.applyExplicit(new ExplicitCommand(new CommandSource(command.eventId(),
                "PURCHASE_RETURN", command.purchaseReturnId(), receipt.warehouseId(), receipt.storeId(),
                businessDate, command.correlationId()), "MIXED", lotLines));
        }
    }
}
