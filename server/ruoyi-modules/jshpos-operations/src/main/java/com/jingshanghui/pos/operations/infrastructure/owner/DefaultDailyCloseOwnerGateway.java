package com.jingshanghui.pos.operations.infrastructure.owner;

import com.jingshanghui.pos.foundation.application.port.StoreIndustryReadPort;
import com.jingshanghui.pos.foundation.application.port.StoreIndustryReadPort.IndustryBinding;
import com.jingshanghui.pos.foundation.domain.CanonicalJson;
import com.jingshanghui.pos.operations.application.model.DailyCloseModels.*;
import com.jingshanghui.pos.operations.application.port.DailyCloseOwnerGateway;
import com.jingshanghui.pos.operations.domain.DailyCloseRules;
import com.jingshanghui.pos.operations.domain.DailyCloseStates.CheckStatus;
import com.jingshanghui.pos.order.application.port.DailyCloseOrderReadPort;
import com.jingshanghui.pos.order.application.port.DailyCloseOrderReadPort.DailyOrderFacts;
import com.jingshanghui.pos.payment.application.port.DailyClosePaymentReadPort;
import com.jingshanghui.pos.payment.application.port.DailyClosePaymentReadPort.DailyPaymentFacts;
import com.jingshanghui.pos.reporting.application.port.DailyCloseReportingReadPort;
import com.jingshanghui.pos.reporting.application.port.DailyCloseReportingReadPort.DailyReportingFacts;
import com.jingshanghui.pos.sync.application.port.DailyCloseSyncReadPort;
import com.jingshanghui.pos.sync.application.port.DailyCloseSyncReadPort.DailySyncFacts;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 组合窄只读端口形成日结清单；任何 Owner 缺失或内容漂移均失败关闭。 */
@Component
@RequiredArgsConstructor
public class DefaultDailyCloseOwnerGateway implements DailyCloseOwnerGateway {
    private static final String ZERO_HASH = "0".repeat(64);
    private final StoreIndustryReadPort stores;
    private final DailyCloseOrderReadPort orders;
    private final DailyClosePaymentReadPort payments;
    private final DailyCloseSyncReadPort sync;
    private final DailyCloseReportingReadPort reporting;

    @Override
    public OwnerSnapshot capture(Long storeId, LocalDate businessDate) {
        DailyCloseRules.store(storeId);
        DailyCloseRules.date(businessDate);
        IndustryBinding store = stores.requireCurrentIndustry(storeId);
        if (store == null || store.zoneId() == null || store.businessDayStart() == null) {
            throw new ServiceException("OPS-OWNER-001: 门店时区或业务日起点不可用", 409);
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(store.zoneId());
        } catch (DateTimeException exception) {
            throw new ServiceException("OPS-OWNER-002: 门店 IANA 时区无效", 409);
        }
        ZonedDateTime localStart = ZonedDateTime.of(businessDate, store.businessDayStart(), zone);
        LocalDateTime fromUtc = localStart.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime toUtc = localStart.plusDays(1).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

        DailyOrderFacts order = required(orders.read(storeId, businessDate), "SHIFT_ORDER");
        DailyPaymentFacts payment = required(payments.read(storeId, fromUtc, toUtc), "PAYMENT_REFUND");
        DailySyncFacts syncFacts = required(sync.read(storeId), "SYNC");
        DailyReportingFacts report = required(reporting.read(storeId, businessDate), "REPORTING");
        DailyCloseRules.money(order.grossMinor(), order.discountMinor(), order.surchargeMinor(), order.receivableMinor());
        requireCurrency(order.currency(), payment.currency(), report.currency());

        Map<String, Object> foundationMap = map(
            "storeId", storeId, "zoneId", store.zoneId(), "businessDayStart", store.businessDayStart().toString(),
            "templateVersion", store.versionNo(), "templateSha256", store.contentSha256());
        Map<String, Object> orderMap = map(
            "orderCount", order.orderCount(), "cancelledOrderCount", order.cancelledOrderCount(),
            "refundCount", order.refundCount(), "grossMinor", order.grossMinor(),
            "discountMinor", order.discountMinor(), "surchargeMinor", order.surchargeMinor(),
            "receivableMinor", order.receivableMinor(), "refundMinor", order.refundMinor(),
            "cashReceivedMinor", order.cashReceivedMinor(), "cashRefundedMinor", order.cashRefundedMinor(),
            "shiftDifferenceMinor", order.shiftDifferenceMinor(), "openShiftCount", order.openShiftCount(),
            "unapprovedCashDifferenceCount", order.unapprovedCashDifferenceCount(), "sourceVersion", order.sourceVersion());
        Map<String, Object> paymentMap = map(
            "succeededPaymentCount", payment.succeededPaymentCount(), "succeededPaymentMinor", payment.succeededPaymentMinor(),
            "succeededRefundCount", payment.succeededRefundCount(), "succeededRefundMinor", payment.succeededRefundMinor(),
            "unknownPaymentCount", payment.unknownPaymentCount(), "unknownRefundCount", payment.unknownRefundCount(),
            "sourceVersion", payment.sourceVersion());
        Map<String, Object> syncMap = map(
            "pendingCount", syncFacts.pendingCount(), "retryCount", syncFacts.retryCount(),
            "conflictCount", syncFacts.conflictCount(), "deadLetterCount", syncFacts.deadLetterCount(),
            "maximumDeviceSequence", syncFacts.maximumDeviceSequence(), "appliedCount", syncFacts.appliedCount());
        Map<String, Object> reportMap = map(
            "orderCount", report.orderCount(), "cancelledOrderCount", report.cancelledOrderCount(),
            "returnCount", report.returnCount(), "grossMinor", report.grossMinor(),
            "discountMinor", report.discountMinor(), "surchargeMinor", report.surchargeMinor(),
            "receivableMinor", report.receivableMinor(), "refundMinor", report.refundMinor(),
            "cashReceivedMinor", report.cashReceivedMinor(), "cashRefundedMinor", report.cashRefundedMinor(),
            "shiftDifferenceMinor", report.shiftDifferenceMinor(), "incompleteLineageCount", report.incompleteLineageCount(),
            "openDifferenceCount", report.openDifferenceCount(), "lineageOwnerCount", report.lineageOwnerCount(),
            "maximumSourceSequence", report.maximumSourceSequence(), "salesProjectionVersion", report.salesProjectionVersion(),
            "inventoryProjectionVersion", report.inventoryProjectionVersion());

        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("businessDate", businessDate.toString());
        canonical.put("foundation", foundationMap);
        canonical.put("order", orderMap);
        canonical.put("payment", paymentMap);
        canonical.put("reporting", reportMap);
        canonical.put("sync", syncMap);

        List<SourceCheckpoint> checkpoints = List.of(
            checkpoint("FOUNDATION", "template-" + store.versionNo(), store.versionNo(), "CURRENT", foundationMap),
            checkpoint("SHIFT_ORDER", "record-" + order.sourceVersion(), order.sourceVersion(), "CURRENT", orderMap),
            checkpoint("PAYMENT_REFUND", "record-" + payment.sourceVersion(), payment.sourceVersion(), "CURRENT", paymentMap),
            checkpoint("SYNC", "device-sequence-" + syncFacts.maximumDeviceSequence(), syncFacts.maximumDeviceSequence(),
                backlog(syncFacts) == 0 ? "CURRENT" : "INCOMPLETE", syncMap),
            checkpoint("REPORTING", report.salesProjectionVersion() + "/" + report.inventoryProjectionVersion(),
                report.maximumSourceSequence(), report.incompleteLineageCount() == 0 ? "CURRENT" : "INCOMPLETE", reportMap)
        );
        List<PreflightFact> checks = checks(order, payment, syncFacts, report, checkpoints);
        long totalRefundCount = Math.addExact(order.refundCount(), payment.succeededRefundCount());
        long totalRefundMinor = Math.addExact(order.refundMinor(), payment.succeededRefundMinor());
        SnapshotAmounts amounts = new SnapshotAmounts("CNY", order.orderCount(), order.cancelledOrderCount(),
            totalRefundCount, order.grossMinor(), order.discountMinor(), order.surchargeMinor(),
            order.receivableMinor(), totalRefundMinor, order.cashReceivedMinor(), order.cashRefundedMinor(),
            payment.succeededPaymentMinor(), payment.succeededRefundMinor(), payment.unknownPaymentCount(),
            payment.unknownRefundCount(), order.shiftDifferenceMinor());
        return new OwnerSnapshot(store.zoneId(), store.businessDayStart(), amounts, checkpoints, checks, canonical);
    }

    private List<PreflightFact> checks(DailyOrderFacts order, DailyPaymentFacts payment, DailySyncFacts syncFacts,
                                       DailyReportingFacts report, List<SourceCheckpoint> checkpoints) {
        List<PreflightFact> values = new ArrayList<>();
        values.add(check("STORE_CONTEXT", "FOUNDATION", order.orderCount() >= 0, "可信门店时区与业务日起点可用", checkpoints.get(0).contentSha256()));
        values.add(check("ALL_SHIFTS_CLOSED", "SHIFT_ORDER", order.openShiftCount() == 0,
            "未关班次数=" + order.openShiftCount(), checkpoints.get(1).contentSha256()));
        values.add(check("CASH_DIFFERENCE_APPROVED", "SHIFT_ORDER", order.unapprovedCashDifferenceCount() == 0,
            "未审批现金差异数=" + order.unapprovedCashDifferenceCount(), checkpoints.get(1).contentSha256()));
        values.add(check("PAYMENT_UNKNOWN_ZERO", "PAYMENT_REFUND", payment.unknownPaymentCount() == 0,
            "UNKNOWN支付数=" + payment.unknownPaymentCount(), checkpoints.get(2).contentSha256()));
        values.add(check("REFUND_UNKNOWN_ZERO", "PAYMENT_REFUND", payment.unknownRefundCount() == 0,
            "UNKNOWN退款数=" + payment.unknownRefundCount(), checkpoints.get(2).contentSha256()));
        values.add(check("SYNC_BACKLOG_ZERO", "SYNC", backlog(syncFacts) == 0,
            "同步待处理/重试/冲突/死信=" + backlog(syncFacts), checkpoints.get(3).contentSha256()));
        values.add(check("REPORTING_CURRENT", "REPORTING", report.incompleteLineageCount() == 0
            && !"UNAVAILABLE".equals(report.salesProjectionVersion()) && !"UNAVAILABLE".equals(report.inventoryProjectionVersion()),
            "投影缺口=" + report.incompleteLineageCount(), checkpoints.get(4).contentSha256()));
        values.add(check("REPORTING_DIFFERENCE_ZERO", "REPORTING", report.openDifferenceCount() == 0,
            "未处理报表差异=" + report.openDifferenceCount(), checkpoints.get(4).contentSha256()));
        boolean lineageReady = order.orderCount() == 0 || report.lineageOwnerCount() >= 5;
        values.add(check("OWNER_LINEAGE_COMPLETE", "REPORTING", lineageReady,
            "已见Owner数=" + report.lineageOwnerCount(), checkpoints.get(4).contentSha256()));
        long totalRefundCount = Math.addExact(order.refundCount(), payment.succeededRefundCount());
        long totalRefundMinor = Math.addExact(order.refundMinor(), payment.succeededRefundMinor());
        boolean totalsMatch = order.orderCount() == report.orderCount()
            && order.cancelledOrderCount() == report.cancelledOrderCount()
            && totalRefundCount == report.returnCount()
            && order.grossMinor() == report.grossMinor() && order.discountMinor() == report.discountMinor()
            && order.surchargeMinor() == report.surchargeMinor() && order.receivableMinor() == report.receivableMinor()
            && totalRefundMinor == report.refundMinor() && order.cashReceivedMinor() == report.cashReceivedMinor()
            && order.cashRefundedMinor() == report.cashRefundedMinor()
            && order.shiftDifferenceMinor() == report.shiftDifferenceMinor();
        values.add(check("AUTHORITATIVE_REPORTING_RECONCILED", "REPORTING", totalsMatch,
            totalsMatch ? "逐字段守恒" : "投影与权威事实不一致", checkpoints.get(4).contentSha256()));
        values.add(new PreflightFact("EXTERNAL_PROVIDER_RECONCILIATION", "PAYMENT_PROVIDER", false, true,
            CheckStatus.BLOCKED, ZERO_HASH, "T2-PAY-002 BLOCKED/UNAVAILABLE；未伪造渠道对账通过"));
        return List.copyOf(values);
    }

    private PreflightFact check(String code, String owner, boolean pass, String message, String hash) {
        return new PreflightFact(code, owner, true, false, pass ? CheckStatus.PASS : CheckStatus.FAIL, hash, message);
    }

    private SourceCheckpoint checkpoint(String owner, String version, long sequence, String status, Map<String, Object> content) {
        return new SourceCheckpoint(owner, version, sequence, status, CanonicalJson.from(content).sha256());
    }

    private long backlog(DailySyncFacts value) {
        return value.pendingCount() + value.retryCount() + value.conflictCount() + value.deadLetterCount();
    }

    private void requireCurrency(String... values) {
        for (String value : values) {
            if (!"CNY".equals(value)) throw new ServiceException("OPS-CURRENCY-001: 商业V1日结只支持CNY", 409);
        }
    }

    private <T> T required(T value, String owner) {
        if (value == null) throw new ServiceException("OPS-OWNER-003: " + owner + " 权威事实不可用", 503);
        return value;
    }

    private Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put(String.valueOf(values[index]), values[index + 1]);
        return result;
    }
}
