package com.jingshanghui.pos.migration.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** 开业资料迁移批次、资料类型与 Owner 检查点的封闭状态。 */
public final class MigrationStates {
    private MigrationStates() {
    }

    /** 迁移批次允许的数据 Owner 资料类型。 */
    public enum DataType {
        /** 商品、条码、基础单位与分类品牌。 */ CATALOG,
        /** 供应商主数据。 */ SUPPLIER,
        /** 按仓库、SKU 和业务日冻结的期初库存。 */ OPENING_INVENTORY,
        /** 最小会员主体与加密身份。 */ MEMBER
    }

    /** 开业资料迁移批次的具名状态机。 */
    public enum BatchState {
        /** 批次已创建，等待文件。 */ UPLOADED,
        /** 文件正在进行安全检查和完整预检。 */ PREFLIGHTING,
        /** 预检存在阻断错误，本批次不可继续导入。 */ PREFLIGHT_FAILED,
        /** 全部必需文件预检通过，等待双人审批。 */ READY,
        /** 两名不同管理员完成审批。 */ APPROVED,
        /** 正沿原行和原幂等键执行 Owner Saga。 */ IMPORTING,
        /** 全部 Owner 行已形成稳定检查点。 */ IMPORTED,
        /** 正在核对 staging 与完整 Owner 检查点。 */ RECONCILING,
        /** 对账零差异，可申请激活。 */ RECONCILED,
        /** 已开始幂等激活，等待最终状态确认。 */ ACTIVATION_PENDING,
        /** 可见版本或批次激活里程碑完成。 */ ACTIVATED,
        /** 尚未形成部分 Owner 效果的可恢复失败。 */ FAILED,
        /** 已有部分 Owner 效果，需要恢复原 Saga 或显式补偿。 */ COMPENSATION_REQUIRED,
        /** 加密 staging 已按规则擦除，Owner 事实与审计保留。 */ CLEANED
    }

    /** 单行 Owner 检查点状态。 */
    public enum CheckpointState {
        /** 尚未确认 Owner 结果。 */ PENDING,
        /** Owner 稳定结果已保存。 */ APPLIED,
        /** Owner 明确失败，禁止静默跳过。 */ FAILED
    }

    private static final Map<BatchState, Set<BatchState>> TRANSITIONS = Map.ofEntries(
        Map.entry(BatchState.UPLOADED, EnumSet.of(BatchState.PREFLIGHTING, BatchState.PREFLIGHT_FAILED)),
        Map.entry(BatchState.PREFLIGHTING, EnumSet.of(BatchState.READY, BatchState.PREFLIGHT_FAILED)),
        Map.entry(BatchState.READY, EnumSet.of(BatchState.APPROVED)),
        Map.entry(BatchState.APPROVED, EnumSet.of(BatchState.IMPORTING)),
        Map.entry(BatchState.IMPORTING, EnumSet.of(BatchState.IMPORTED, BatchState.FAILED,
            BatchState.COMPENSATION_REQUIRED)),
        Map.entry(BatchState.FAILED, EnumSet.of(BatchState.IMPORTING, BatchState.COMPENSATION_REQUIRED)),
        Map.entry(BatchState.COMPENSATION_REQUIRED, EnumSet.of(BatchState.IMPORTING)),
        Map.entry(BatchState.IMPORTED, EnumSet.of(BatchState.RECONCILING)),
        Map.entry(BatchState.RECONCILING, EnumSet.of(BatchState.RECONCILED, BatchState.COMPENSATION_REQUIRED)),
        Map.entry(BatchState.RECONCILED, EnumSet.of(BatchState.ACTIVATION_PENDING)),
        Map.entry(BatchState.ACTIVATION_PENDING, EnumSet.of(BatchState.ACTIVATED, BatchState.COMPENSATION_REQUIRED)),
        Map.entry(BatchState.ACTIVATED, EnumSet.of(BatchState.CLEANED))
    );

    public static boolean canTransition(BatchState from, BatchState to) {
        return from == to || TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }
}
