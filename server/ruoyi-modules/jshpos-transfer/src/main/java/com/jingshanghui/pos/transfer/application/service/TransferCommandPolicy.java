package com.jingshanghui.pos.transfer.application.service;

import com.jingshanghui.pos.catalog.application.port.InventoryCatalogSnapshotPort.SkuUnitSnapshot;
import com.jingshanghui.pos.transfer.application.model.TransferCommands.*;
import com.jingshanghui.pos.transfer.domain.TransferHash;
import com.jingshanghui.pos.transfer.domain.TransferRules;
import org.dromara.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * 调拨命令校验、精确单位换算与幂等摘要策略。
 *
 * <p>该策略不访问 Mapper、不控制事务；公开 API、错误码、摘要顺序与调拨状态机保持不变。</p>
 */
final class TransferCommandPolicy {
    void validateCreate(CreateTransfer command) {
        TransferRules.ulid(command.transferId(), "transferId");
        TransferRules.distinctWarehouses(command.sourceWarehouseId(), command.destinationWarehouseId());
        TransferRules.text(command.correlationId(), 96, "TRF-INPUT-001");
        if (command.sourceStoreId() == null || command.sourceStoreId() <= 0 || command.destinationStoreId() == null
            || command.destinationStoreId() <= 0 || command.lines().isEmpty() || command.lines().size() > 500
            || new HashSet<>(command.lines().stream().map(CreateLine::transferLineId).toList()).size() != command.lines().size()
            || new HashSet<>(command.lines().stream().map(CreateLine::skuId).toList()).size() != command.lines().size()) {
            throw new ServiceException("TRF-INPUT-003: 调拨主体、行标识或 SKU 不合法", 409);
        }
    }

    void validateState(StateCommand command) {
        TransferRules.ulid(command.transferId(), "transferId");
        TransferRules.ulid(command.commandId(), "commandId");
        TransferRules.text(command.reason(), 256, "TRF-INPUT-002");
        TransferRules.text(command.correlationId(), 96, "TRF-INPUT-001");
    }

    void validateDispatch(DispatchTransfer command) {
        TransferRules.ulid(command.transferId(), "transferId");
        TransferRules.ulid(command.dispatchId(), "dispatchId");
        TransferRules.ulid(command.eventId(), "eventId");
        TransferRules.text(command.correlationId(), 96, "TRF-INPUT-001");
        if (command.lotSplits().size() > 1000) throw new ServiceException("TRF-LOT-011: 发出批次拆分超过上限", 409);
        command.lotSplits().forEach(split -> {
            TransferRules.ulid(split.transferLineId(), "transferLineId");
            TransferRules.ulid(split.lotId(), "lotId");
            TransferRules.quantity(split.baseQuantity(), "lotBaseQuantity");
        });
    }

    void validateReceive(ReceiveTransfer command) {
        TransferRules.ulid(command.transferId(), "transferId");
        TransferRules.ulid(command.receiptId(), "receiptId");
        TransferRules.ulid(command.eventId(), "eventId");
        TransferRules.text(command.correlationId(), 96, "TRF-INPUT-001");
        if (command.lines().isEmpty() || command.lines().size() > 500
            || new HashSet<>(command.lines().stream().map(ReceiveLine::receiptLineId).toList()).size() != command.lines().size()
            || new HashSet<>(command.lines().stream().map(ReceiveLine::transferLineId).toList()).size() != command.lines().size()) {
            throw new ServiceException("TRF-RECEIPT-003: 收货行必须唯一且为1至500项", 409);
        }
        command.lines().forEach(line -> { TransferRules.ulid(line.receiptLineId(), "receiptLineId");
            TransferRules.ulid(line.transferLineId(), "transferLineId"); });
        if (command.lotSplits().size() > 1000) throw new ServiceException("TRF-LOT-012: 收货批次拆分超过上限", 409);
        command.lotSplits().forEach(split -> {
            TransferRules.ulid(split.receiptLineId(), "receiptLineId");
            TransferRules.ulid(split.sourceLotId(), "sourceLotId");
            TransferRules.quantity(split.baseQuantity(), "lotBaseQuantity");
        });
    }

    void validateDifference(ResolveDifference command) {
        TransferRules.ulid(command.transferId(), "transferId");
        TransferRules.ulid(command.commandId(), "commandId");
        TransferRules.text(command.reason(), 256, "TRF-DIFF-004");
        TransferRules.text(command.correlationId(), 96, "TRF-INPUT-001");
        if (command.lines().isEmpty() || command.lines().size() > 500
            || new HashSet<>(command.lines().stream().map(DifferenceLine::transferLineId).toList()).size() != command.lines().size()) {
            throw new ServiceException("TRF-DIFF-005: 差异行必须唯一且为1至500项", 409);
        }
    }

    BigDecimal toBase(BigDecimal value, SkuUnitSnapshot unit) {
        try {
            return value.multiply(BigDecimal.valueOf(unit.numerator()))
                .divide(BigDecimal.valueOf(unit.denominator()), TransferRules.QUANTITY_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new ServiceException("TRF-UNIT-001: 单位换算不能精确到六位小数", 409);
        }
    }

    String hashCreate(CreateTransfer command) {
        List<Object> values = new ArrayList<>(List.of(command.transferId(), command.sourceStoreId(),
            command.sourceWarehouseId(), command.destinationStoreId(), command.destinationWarehouseId(),
            command.reason()));
        command.lines().stream().sorted(Comparator.comparing(CreateLine::transferLineId)).forEach(line -> {
            values.add(line.transferLineId()); values.add(line.skuId()); values.add(line.unitId());
            values.add(line.requestedQuantity());
        });
        return TransferHash.sha256(TransferHash.canonical(values));
    }

    String stateHash(StateCommand command, String type) {
        return TransferHash.sha256(TransferHash.canonical(List.of(type, command.transferId(), command.commandId(),
            command.expectedVersion(), command.reason())));
    }

    String hashReceive(ReceiveTransfer command) {
        List<Object> values = new ArrayList<>(List.of(command.transferId(), command.receiptId(), command.eventId(),
            command.expectedVersion(), command.finalReceipt()));
        command.lines().stream().sorted(Comparator.comparing(ReceiveLine::receiptLineId)).forEach(line -> {
            values.add(line.receiptLineId()); values.add(line.transferLineId()); values.add(line.receivedQuantity());
        });
        command.lotSplits().stream().sorted(Comparator.comparing(ReceiveLotSplit::receiptLineId)
            .thenComparing(ReceiveLotSplit::sourceLotId)).forEach(line -> {
                values.add(line.receiptLineId()); values.add(line.sourceLotId()); values.add(line.baseQuantity());
            });
        return TransferHash.sha256(TransferHash.canonical(values));
    }

    String hashDispatch(DispatchTransfer command) {
        List<Object> values = new ArrayList<>(List.of(command.transferId(), command.dispatchId(), command.eventId(),
            command.expectedVersion()));
        command.lotSplits().stream().sorted(Comparator.comparing(DispatchLotSplit::transferLineId)
            .thenComparing(DispatchLotSplit::lotId)).forEach(line -> {
                values.add(line.transferLineId()); values.add(line.lotId()); values.add(line.baseQuantity());
            });
        return TransferHash.sha256(TransferHash.canonical(values));
    }

    String hashDifference(ResolveDifference command) {
        List<Object> values = new ArrayList<>(List.of(command.transferId(), command.commandId(),
            command.expectedVersion(), command.reason()));
        command.lines().stream().sorted(Comparator.comparing(DifferenceLine::transferLineId)).forEach(line -> {
            values.add(line.transferLineId()); values.add(line.differenceQuantity()); values.add(line.differenceReason());
        });
        return TransferHash.sha256(TransferHash.canonical(values));
    }
}
