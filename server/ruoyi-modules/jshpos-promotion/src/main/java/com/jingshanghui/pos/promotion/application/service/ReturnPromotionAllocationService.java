package com.jingshanghui.pos.promotion.application.service;

import com.jingshanghui.pos.promotion.application.model.PromotionCommands.AllocateRefund;
import com.jingshanghui.pos.promotion.application.model.PromotionCommands.RefundLine;
import com.jingshanghui.pos.promotion.application.port.ReturnPromotionAllocationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 将 Return Owner 端口命令适配为 Promotion Owner 原快照退款分摊命令。 */
@Service
@RequiredArgsConstructor
public class ReturnPromotionAllocationService implements ReturnPromotionAllocationPort {

    private final PromotionTransactionService transactions;

    @Override
    public PreviewResult preview(PreviewCommand command) {
        var result = transactions.previewRefund(command.snapshotId(), command.lines().stream()
            .map(line -> new RefundLine(line.lineId(), line.quantity())).toList());
        return new PreviewResult(result.snapshotId(), result.grossAmountMinor(),
            result.recoveredDiscountMinor(), result.refundableAmountMinor(), result.lines().stream()
            .map(line -> new AllocatedLine(line.lineId(), line.quantity(), line.grossAmountMinor(),
                line.recoveredDiscountMinor(), line.refundableAmountMinor(), line.cumulativeQuantity(),
                line.cumulativePayableAmountMinor())).toList());
    }

    @Override
    public AllocationResult allocate(AllocationCommand command) {
        var result = transactions.allocateRefund(new AllocateRefund(command.eventId(), command.snapshotId(),
            command.refundId(), command.lines().stream()
            .map(line -> new RefundLine(line.lineId(), line.quantity())).toList(), command.correlationId()));
        return new AllocationResult(result.refundId(), result.snapshotId(), result.grossAmountMinor(),
            result.recoveredDiscountMinor(), result.refundableAmountMinor(), result.lines().stream()
            .map(line -> new AllocatedLine(line.lineId(), line.quantity(), line.grossAmountMinor(),
                line.recoveredDiscountMinor(), line.refundableAmountMinor(), line.cumulativeQuantity(),
                line.cumulativePayableAmountMinor())).toList());
    }
}
