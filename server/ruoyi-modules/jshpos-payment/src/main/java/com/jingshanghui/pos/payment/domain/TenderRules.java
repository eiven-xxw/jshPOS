package com.jingshanghui.pos.payment.domain;

import com.jingshanghui.pos.payment.domain.TenderStates.AllocationStatus;
import com.jingshanghui.pos.payment.domain.TenderStates.PlanStatus;
import com.jingshanghui.pos.payment.domain.TenderStates.TenderType;
import org.dromara.common.core.exception.ServiceException;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 组合支付金额守恒、严格顺序、失败恢复与原份额退款分配规则。 */
public final class TenderRules {

    private static final int MIN_ALLOCATIONS = 2;
    private static final int MAX_ALLOCATIONS = 8;

    private TenderRules() {
    }

    /** 冻结前规范化份额；现金至多一个且必须最后，合计必须严格等于应收。 */
    public static List<AllocationSpec> validatePlan(long receivableMinor, String currency,
                                                    List<AllocationSpec> input) {
        PaymentRules.requirePositiveAmount(receivableMinor, "receivableAmountMinor");
        PaymentRules.requireCurrency(currency);
        if (!"CNY".equals(currency)) {
            throw invalid("TENDER-CURRENCY-001", "商业 V1 组合支付只支持 CNY");
        }
        if (input == null || input.size() < MIN_ALLOCATIONS || input.size() > MAX_ALLOCATIONS) {
            throw invalid("TENDER-PLAN-001", "组合支付份额数量必须为 2 至 8");
        }
        if (input.stream().anyMatch(java.util.Objects::isNull)) {
            throw invalid("TENDER-PLAN-002", "支付份额不能为空");
        }
        List<AllocationSpec> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparingInt(AllocationSpec::sequenceNo));
        long sum = 0;
        int cashCount = 0;
        Set<String> allocationIds = new HashSet<>();
        for (int index = 0; index < sorted.size(); index++) {
            AllocationSpec item = sorted.get(index);
            PaymentRules.requireUlid(item.allocationId(), "allocationId");
            if (!allocationIds.add(item.allocationId())) {
                throw invalid("TENDER-PLAN-003", "支付份额标识不得重复");
            }
            PaymentRules.requirePositiveAmount(item.amountMinor(), "allocationAmountMinor");
            if (item.sequenceNo() != index + 1 || item.tenderType() == null) {
                throw invalid("TENDER-SEQUENCE-001", "份额顺序必须从 1 连续递增");
            }
            if (item.tenderType() == TenderType.CASH) cashCount++;
            sum = Math.addExact(sum, item.amountMinor());
        }
        if (cashCount > 1) {
            throw invalid("TENDER-CASH-001", "现金份额至多一个");
        }
        if (cashCount == 1 && sorted.get(sorted.size() - 1).tenderType() != TenderType.CASH) {
            throw invalid("TENDER-CASH-002", "现金份额必须是最后一个份额");
        }
        if (sum != receivableMinor) {
            throw invalid("TENDER-AMOUNT-002", "份额合计必须等于订单应收");
        }
        return List.copyOf(sorted);
    }

    /**
     * 生成跨 Java/Dart 一致的冻结计划摘要；字段顺序属于正式契约，禁止调用方自行拼接。
     *
     * @param planId 支付计划 ULID
     * @param orderId 原订单 ULID
     * @param orderSnapshotSha256 原订单不可变快照摘要
     * @param storeId 可信门店主键
     * @param terminalId 可信终端 ULID
     * @param shiftId 冻结班次 ULID
     * @param businessDate 冻结业务日
     * @param receivableMinor 订单应收金额，单位分
     * @param currency 币种，商业 V1 固定 CNY
     * @param allocations 已按 sequenceNo 规范化的冻结份额
     * @return 小写 SHA-256
     */
    public static String contentSha256(String planId, String orderId, String orderSnapshotSha256,
                                       Long storeId, String terminalId, String shiftId,
                                       LocalDate businessDate, long receivableMinor, String currency,
                                       List<AllocationSpec> allocations) {
        List<Object> values = new ArrayList<>(List.of(planId, orderId, orderSnapshotSha256, storeId,
            terminalId, shiftId, businessDate, receivableMinor, currency));
        allocations.forEach(item -> values.addAll(List.of(item.allocationId(), item.sequenceNo(),
            item.tenderType(), item.amountMinor())));
        return PaymentHash.sha256(PaymentHash.canonical(values));
    }

    /** 只有所有前序份额已成功且目标仍为计划态时，才允许首次收取。 */
    public static void requireCollectable(List<AllocationState> allocations, String allocationId) {
        AllocationState target = allocations.stream().filter(item -> item.allocationId().equals(allocationId))
            .findFirst().orElseThrow(() -> invalid("TENDER-NOT-VISIBLE", "支付份额不存在或不可见"));
        if (target.status() == AllocationStatus.PROCESSING || target.status() == AllocationStatus.UNKNOWN) {
            throw invalid("TENDER-UNKNOWN-001", "处理中或未知份额只能查询原命令，不得重新收取");
        }
        if (target.status() != AllocationStatus.PLANNED) {
            throw invalid("TENDER-STATE-001", "当前份额状态禁止收取");
        }
        boolean blocked = allocations.stream().anyMatch(item -> item.sequenceNo() < target.sequenceNo()
            && item.status() != AllocationStatus.SUCCEEDED);
        if (blocked) {
            throw invalid("TENDER-SEQUENCE-002", "前序份额尚未成功，禁止越序收取");
        }
    }

    /** 只有冻结或已开始收取的计划可以继续；UNKNOWN 必须先观察原命令收敛。 */
    public static void requirePlanCollectable(PlanStatus status) {
        if (status == PlanStatus.UNKNOWN) {
            throw invalid("TENDER-UNKNOWN-001", "未知计划只能查询或观察原命令");
        }
        if (status != PlanStatus.FROZEN && status != PlanStatus.COLLECTING) {
            throw invalid("TENDER-STATE-004", "当前支付计划状态禁止收取份额");
        }
    }

    /** 已出现成功、处理中或 UNKNOWN 的计划不得原地取消，只能走原介质反向处置。 */
    public static void requireCancellable(PlanStatus planStatus, List<AllocationState> allocations) {
        if (planStatus == PlanStatus.PAID || planStatus == PlanStatus.CANCELLED
            || allocations.stream().anyMatch(item -> item.status() == AllocationStatus.SUCCEEDED
                || item.status() == AllocationStatus.PROCESSING || item.status() == AllocationStatus.UNKNOWN)) {
            throw invalid("TENDER-CANCEL-001", "plan has succeeded or occupied allocation");
        }
        if (allocations.stream().anyMatch(item -> item.status() != AllocationStatus.PLANNED
            && item.status() != AllocationStatus.FAILED && item.status() != AllocationStatus.CANCELLED)) {
            throw invalid("TENDER-CANCEL-002", "allocation state cannot be cancelled");
        }
    }

    /** 根据各份额资金事实确定计划投影，不允许 UNKNOWN 被失败覆盖。 */
    public static PlanProjection project(List<AllocationState> allocations, long receivableMinor) {
        long succeeded = 0;
        long occupied = 0;
        boolean unknown = false;
        boolean collecting = false;
        boolean failed = false;
        for (AllocationState item : allocations) {
            if (item.status() == AllocationStatus.SUCCEEDED) {
                succeeded = Math.addExact(succeeded, item.amountMinor());
                occupied = Math.addExact(occupied, item.amountMinor());
            } else if (item.status() == AllocationStatus.PROCESSING || item.status() == AllocationStatus.UNKNOWN) {
                occupied = Math.addExact(occupied, item.amountMinor());
                unknown |= item.status() == AllocationStatus.UNKNOWN;
                collecting |= item.status() == AllocationStatus.PROCESSING;
            } else if (item.status() == AllocationStatus.FAILED) {
                failed = true;
            }
        }
        PlanStatus status;
        if (succeeded == receivableMinor) status = PlanStatus.PAID;
        else if (unknown) status = PlanStatus.UNKNOWN;
        else if (collecting || succeeded > 0) status = PlanStatus.COLLECTING;
        else if (failed) status = PlanStatus.FAILED;
        else status = PlanStatus.FROZEN;
        return new PlanProjection(status, succeeded, occupied);
    }

    /** 按原成功份额比例恢复退款，最后一个份额吸收整数余数。 */
    public static List<RefundShare> allocateRefund(long refundMinor, List<AllocationState> allocations) {
        PaymentRules.requirePositiveAmount(refundMinor, "refundAmountMinor");
        List<AllocationState> succeeded = allocations.stream()
            .filter(item -> item.status() == AllocationStatus.SUCCEEDED)
            .sorted(Comparator.comparingInt(AllocationState::sequenceNo)).toList();
        long paid = succeeded.stream().mapToLong(AllocationState::amountMinor).sum();
        if (succeeded.isEmpty() || refundMinor > paid) {
            throw invalid("TENDER-REFUND-001", "退款金额超过原成功份额上限");
        }
        List<RefundShare> result = new ArrayList<>();
        long allocated = 0;
        for (int index = 0; index < succeeded.size(); index++) {
            AllocationState item = succeeded.get(index);
            long share = index == succeeded.size() - 1 ? refundMinor - allocated
                : BigInteger.valueOf(refundMinor).multiply(BigInteger.valueOf(item.amountMinor()))
                    .divide(BigInteger.valueOf(paid)).longValueExact();
            allocated = Math.addExact(allocated, share);
            result.add(new RefundShare(item.allocationId(), item.tenderType(), share));
        }
        return List.copyOf(result);
    }

    private static ServiceException invalid(String code, String message) {
        return new ServiceException(code + ": " + message, 409);
    }

    /**
     * 冻结份额输入。
     *
     * @param allocationId 份额 ULID
     * @param sequenceNo 从 1 连续递增的收取顺序
     * @param tenderType 原支付介质类别
     * @param amountMinor 冻结金额，单位分
     */
    public record AllocationSpec(String allocationId, int sequenceNo, TenderType tenderType, long amountMinor) {
    }

    /**
     * 计算计划投影时使用的权威份额状态。
     *
     * @param allocationId 份额 ULID
     * @param sequenceNo 冻结收取顺序
     * @param tenderType 原支付介质类别
     * @param status 当前权威状态
     * @param amountMinor 冻结金额，单位分
     */
    public record AllocationState(String allocationId, int sequenceNo, TenderType tenderType,
                                  AllocationStatus status, long amountMinor) {
    }

    /**
     * 支付计划金额投影。
     *
     * @param status 计划派生状态
     * @param succeededAmountMinor 已确认成功金额，单位分
     * @param occupiedAmountMinor 成功、处理中和 UNKNOWN 的占额，单位分
     */
    public record PlanProjection(PlanStatus status, long succeededAmountMinor, long occupiedAmountMinor) {
    }

    /**
     * 原介质退款分摊结果。
     *
     * @param allocationId 原成功份额 ULID
     * @param tenderType 必须原路退回的介质类别
     * @param amountMinor 本份额退款金额，单位分
     */
    public record RefundShare(String allocationId, TenderType tenderType, long amountMinor) {
    }
}
