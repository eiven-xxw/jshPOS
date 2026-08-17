package com.jingshanghui.pos.reporting.interfaces.rest;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationCommands;
import com.jingshanghui.pos.reporting.application.model.PaymentReconciliationViews;
import com.jingshanghui.pos.reporting.application.model.ReportingCommands.*;
import com.jingshanghui.pos.reporting.application.model.ReportingViews.*;
import com.jingshanghui.pos.reporting.application.service.*;
import com.jingshanghui.pos.reporting.interfaces.rest.dto.ReportingRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

/** Gate 5D Reporting API；Controller 只做协议校验和命令转换，不包含报表算法或 Mapper。 */
@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class ReportingController {
    private final ReportingProjectionService projectionService;
    private final ReportQueryService queryService;
    private final ReportExportService exportService;
    private final ReportingDifferenceService differenceService;
    private final PaymentReconciliationService paymentReconciliationService;

    @PostMapping("/reporting/source-events")
    @SaCheckPermission("report:projection:ingest")
    @Log(title="消费报表来源事件", businessType=BusinessType.INSERT, isSaveRequestData=false, isSaveResponseData=false)
    public R<SourceApplyView> ingest(@Valid @RequestBody ReportingRequests.SourceEvent request) {
        var sales = request.sales() == null ? null : new SalesDelta(request.sales().orderCount(),
            request.sales().cancelledOrderCount(), request.sales().returnCount(), request.sales().grossMinor(),
            request.sales().discountMinor(), request.sales().surchargeMinor(), request.sales().receivableMinor(),
            request.sales().refundMinor(), request.sales().cashReceivedMinor(), request.sales().cashRefundedMinor(),
            request.sales().shiftDifferenceMinor(), request.sales().promotionSnapshotCount());
        var inventory = request.inventoryCost() == null ? null : new InventoryCostDelta(
            request.inventoryCost().onHandDelta(), request.inventoryCost().availableDelta(),
            request.inventoryCost().reservedDelta(), request.inventoryCost().ledgerQuantityDelta(),
            request.inventoryCost().purchaseQuantityDelta(), request.inventoryCost().stocktakeQuantityDelta(),
            request.inventoryCost().transferQuantityDelta(), request.inventoryCost().inventoryValueDeltaMinor(),
            request.inventoryCost().cogsDeltaMinor(), request.inventoryCost().purchaseCostDeltaMinor(),
            request.inventoryCost().stocktakeCostDeltaMinor(), request.inventoryCost().transferCostDeltaMinor());
        return R.ok(projectionService.ingest(new SourceEvent(request.sourceEventId(), request.sourceOwner(),
            request.sourceAggregateId(), request.sourceSequence(), request.partitionKey(), request.schemaVersion(),
            request.projectionVersion(), request.contentSha256(), request.occurredAt(), request.businessDate(),
            request.orgId(), request.storeId(), request.terminalId(), request.cashierId(), request.warehouseId(),
            request.skuId(), request.currency(), request.metricFamily(), sales, inventory, request.correlationId())));
    }

    @GetMapping("/reports/sales-daily")
    @SaCheckPermission("report:operation:read")
    public R<List<SalesDailyView>> sales(@RequestParam LocalDate fromDate, @RequestParam LocalDate toDate,
                                         @RequestParam @Positive Long storeId,
                                         @RequestParam(required=false) @Size(max=64) String terminalId,
                                         @RequestParam(required=false) @Positive Long cashierId) {
        return R.ok(queryService.sales(new SalesQuery(fromDate, toDate, storeId, terminalId, cashierId)));
    }

    @GetMapping("/reports/inventory-cost-daily")
    @SaCheckPermission("report:operation:read")
    public R<List<InventoryCostDailyView>> inventory(@RequestParam LocalDate fromDate,
                                                      @RequestParam LocalDate toDate,
                                                      @RequestParam @Positive Long storeId,
                                                      @RequestParam(required=false) @Pattern(regexp=ReportingRequests.ULID) String warehouseId,
                                                      @RequestParam(required=false) @Positive Long skuId) {
        return R.ok(queryService.inventoryCost(new InventoryCostQuery(fromDate, toDate, storeId, warehouseId, skuId)));
    }

    @PostMapping("/reporting/rebuilds")
    @SaCheckPermission("report:projection:rebuild")
    @Log(title="重建报表投影", businessType=BusinessType.UPDATE, isSaveRequestData=false)
    public R<RebuildView> rebuild(@Valid @RequestBody ReportingRequests.Rebuild request) {
        return R.ok(projectionService.rebuild(new Rebuild(request.rebuildId(), request.projectionVersion(),
            request.fromDate(), request.toDate(), request.correlationId())));
    }

    @PostMapping("/report-exports")
    @SaCheckPermission("report:export:request")
    @Log(title="申请报表导出", businessType=BusinessType.INSERT, isSaveRequestData=false)
    public R<ExportView> requestExport(@Valid @RequestBody ReportingRequests.Export request) {
        return R.ok(exportService.request(new ExportRequest(request.exportId(), request.reportType(),
            request.fromDate(), request.toDate(), new HashSet<>(request.storeIds()), new HashSet<>(request.fields()),
            request.correlationId())));
    }

    @GetMapping("/report-exports/{exportId}")
    @SaCheckPermission("report:export:request")
    public R<ExportView> getExport(@PathVariable @Pattern(regexp=ReportingRequests.ULID) String exportId) {
        return R.ok(exportService.get(exportId));
    }

    @PostMapping("/report-exports/{exportId}/approve")
    @SaCheckPermission("report:export:approve")
    @Log(title="审批报表导出", businessType=BusinessType.UPDATE, isSaveRequestData=false)
    public R<ExportView> approve(@PathVariable @Pattern(regexp=ReportingRequests.ULID) String exportId,
                                 @Valid @RequestBody ReportingRequests.Approval request) {
        return R.ok(exportService.approve(new ExportApproval(exportId, request.approved(), request.reason(),
            request.expectedVersion(), request.correlationId())));
    }

    @PostMapping("/report-exports/{exportId}/generate")
    @SaCheckPermission("report:export:generate")
    @Log(title="生成报表导出", businessType=BusinessType.UPDATE, isSaveRequestData=false)
    public R<ExportView> generate(@PathVariable @Pattern(regexp=ReportingRequests.ULID) String exportId,
                                  @Valid @RequestBody ReportingRequests.Generate request) {
        return R.ok(exportService.generate(new ExportGenerate(exportId, request.expectedVersion(),
            request.correlationId())));
    }

    @PostMapping("/report-exports/{exportId}/download-token")
    @SaCheckPermission("report:export:download")
    @Log(title="签发报表下载令牌", businessType=BusinessType.UPDATE, isSaveRequestData=false, isSaveResponseData=false)
    public R<DownloadTokenView> token(@PathVariable @Pattern(regexp=ReportingRequests.ULID) String exportId) {
        return R.ok(exportService.issueDownloadToken(exportId));
    }

    @GetMapping("/report-exports/{exportId}/download")
    @SaCheckPermission("report:export:download")
    @Log(title="下载报表制品", businessType=BusinessType.EXPORT, isSaveRequestData=false, isSaveResponseData=false)
    public ResponseEntity<byte[]> download(@PathVariable @Pattern(regexp=ReportingRequests.ULID) String exportId,
                                            @RequestParam @Size(min=32,max=128) String token) {
        DownloadArtifact artifact = exportService.download(exportId, token);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(artifact.contentType()))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + artifact.fileName() + "\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store").body(artifact.content());
    }

    @GetMapping("/reporting/differences")
    @SaCheckPermission("report:repair:manage")
    public R<List<DifferenceView>> differences(@RequestParam(defaultValue="100") @Min(1) @Max(200) int limit) {
        return R.ok(differenceService.list(limit));
    }

    @PostMapping("/reporting/differences/{differenceId}/transitions")
    @SaCheckPermission("report:repair:manage")
    @Log(title="处理报表差异", businessType=BusinessType.UPDATE, isSaveRequestData=false)
    public R<DifferenceView> transition(@PathVariable @Pattern(regexp=ReportingRequests.ULID) String differenceId,
                                        @Valid @RequestBody ReportingRequests.DifferenceState request) {
        return R.ok(differenceService.transition(new DifferenceTransition(differenceId, request.toState(),
            request.reason(), request.expectedVersion(), request.correlationId())));
    }

    @PostMapping("/reporting/payment-facts")
    @SaCheckPermission("report:payment:ingest")
    @Log(title="消费Provider无关支付退款事实", businessType=BusinessType.INSERT,
        isSaveRequestData=false, isSaveResponseData=false)
    public R<PaymentReconciliationViews.IngestView> ingestPaymentFact(
            @Valid @RequestBody ReportingRequests.PaymentFact request) {
        return R.ok(paymentReconciliationService.ingestFact(new PaymentReconciliationCommands.PaymentFact(
            request.sourceEventId(), request.sourceOwner(), request.sourceSequence(), request.partitionKey(),
            request.schemaVersion(), request.contentSha256(), request.occurredAt(), request.businessDate(),
            request.orgId(), request.storeId(), request.terminalId(), request.factType(),
            request.reconciliationKey(), request.orderId(), request.amountMinor(), request.currency(),
            request.lifecycleStatus(), request.correlationId())));
    }

    @PostMapping("/reporting/internal-synthetic-bills")
    @SaCheckPermission("report:bill:synthetic-import")
    @Log(title="导入内部合成账单", businessType=BusinessType.IMPORT,
        isSaveRequestData=false, isSaveResponseData=false)
    public R<PaymentReconciliationViews.IngestView> ingestSyntheticBill(
            @Valid @RequestBody ReportingRequests.SyntheticBill request) {
        return R.ok(paymentReconciliationService.ingestSyntheticBill(
            new PaymentReconciliationCommands.SyntheticBillEntry(request.billEntryId(), request.batchId(),
                request.sourceType(), request.synthetic(), request.schemaVersion(), request.contentSha256(),
                request.businessDate(), request.orgId(), request.storeId(), request.terminalId(), request.factType(),
                request.reconciliationKey(), request.amountMinor(), request.currency(), request.lifecycleStatus(),
                request.correlationId())));
    }

    @GetMapping("/reports/payment-reconciliation")
    @SaCheckPermission("report:payment-reconciliation:read")
    public R<List<PaymentReconciliationViews.ReconciliationView>> paymentReconciliation(
            @RequestParam LocalDate fromDate, @RequestParam LocalDate toDate,
            @RequestParam @Positive Long storeId,
            @RequestParam(required=false) @Pattern(regexp="^(MATCHED|MISSING_BILL|MISSING_INTERNAL|AMOUNT_MISMATCH|CURRENCY_MISMATCH|STATUS_MISMATCH|BUSINESS_DATE_MISMATCH)$") String differenceType,
            @RequestParam(required=false) @Pattern(regexp="^(MATCHED|OPEN|ASSIGNED|RESOLVED|IGNORED)$") String handlingState) {
        return R.ok(paymentReconciliationService.query(new PaymentReconciliationCommands.Query(fromDate, toDate,
            storeId, differenceType, handlingState)));
    }

    @GetMapping("/reporting/payment-reconciliation/{reconciliationId}/audit")
    @SaCheckPermission("report:payment-reconciliation:read")
    public R<List<PaymentReconciliationViews.AuditView>> paymentReconciliationAudit(
            @PathVariable @Pattern(regexp=ReportingRequests.ULID) String reconciliationId) {
        return R.ok(paymentReconciliationService.audit(reconciliationId));
    }

    @PostMapping("/reporting/payment-reconciliation/{reconciliationId}/transitions")
    @SaCheckPermission("report:payment-reconciliation:manage")
    @Log(title="处理支付退款对账差异", businessType=BusinessType.UPDATE, isSaveRequestData=false)
    public R<PaymentReconciliationViews.ReconciliationView> transitionPaymentReconciliation(
            @PathVariable @Pattern(regexp=ReportingRequests.ULID) String reconciliationId,
            @Valid @RequestBody ReportingRequests.ReconciliationState request) {
        return R.ok(paymentReconciliationService.transition(new PaymentReconciliationCommands.Transition(
            reconciliationId, request.toState(), request.reason(), request.expectedVersion(),
            request.correlationId())));
    }

    @PostMapping("/reporting/payment-reconciliation/rebuilds")
    @SaCheckPermission("report:projection:rebuild")
    @Log(title="重建支付退款对账投影", businessType=BusinessType.UPDATE, isSaveRequestData=false)
    public R<PaymentReconciliationViews.RebuildView> rebuildPaymentReconciliation(
            @Valid @RequestBody ReportingRequests.ReconciliationRebuild request) {
        return R.ok(paymentReconciliationService.rebuild(new PaymentReconciliationCommands.Rebuild(
            request.rebuildId(), request.fromDate(), request.toDate(), request.correlationId())));
    }
}
