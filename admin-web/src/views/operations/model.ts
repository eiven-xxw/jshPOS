import type { UnitInput } from '@/api/catalog/types';

/** 商品多单位表单行；只保存用户输入，不承载价格、库存或促销计算。 */
export interface ProductUnitDraft {
  rowKey: string;
  unitId: string;
  ratioNumerator: number;
  ratioDenominator: number;
  primary: boolean;
  barcodesText: string;
}

/** 经营工作台只读计数，均来自正式 Owner API 的可见范围。 */
export interface OperationsSummary {
  orgCount: number;
  storeCount: number;
  productCount: number;
  configCount: number;
  preparingStoreCount: number;
  inactiveProductCount: number;
  deniedAuditCount: number;
}

/** 将多单位表单转为 Catalog API 契约；不读取或附加 tenant_id。 */
export const normalizeProductUnits = (drafts: ProductUnitDraft[]): UnitInput[] => {
  if (drafts.length === 0 || drafts.length > 50 || drafts.filter((item) => item.primary).length !== 1) {
    throw new Error('CAT-UNIT-UI-001: 商品必须且只能有一个主单位');
  }
  const unitIds = new Set<string>();
  const allBarcodes = new Set<string>();
  return drafts.map((draft) => {
    const unitId = draft.unitId.trim();
    if (!/^[1-9][0-9]{0,18}$/.test(unitId) || unitIds.has(unitId)) {
      throw new Error('CAT-UNIT-UI-002: 单位 ID 无效或重复');
    }
    unitIds.add(unitId);
    if (
      !Number.isSafeInteger(draft.ratioNumerator) ||
      !Number.isSafeInteger(draft.ratioDenominator) ||
      draft.ratioNumerator <= 0 ||
      draft.ratioDenominator <= 0
    ) {
      throw new Error('CAT-UNIT-UI-003: 单位换算必须使用正整数分子分母');
    }
    const barcodes = draft.barcodesText
      .split(/[\s,，;；]+/)
      .map((value) => value.trim())
      .filter(Boolean);
    for (const barcode of barcodes) {
      if (barcode.length > 64 || allBarcodes.has(barcode)) {
        throw new Error('CAT-UNIT-UI-004: 条码过长或在商品内重复');
      }
      allBarcodes.add(barcode);
    }
    return {
      unitId,
      ratioNumerator: draft.ratioNumerator,
      ratioDenominator: draft.ratioDenominator,
      primary: draft.primary,
      barcodes
    };
  });
};

/** 汇总只读列表状态；不得把工作台计数反写业务 Owner。 */
export const buildOperationsSummary = (input: {
  orgStatuses: string[];
  storeStatuses: string[];
  productStatuses: string[];
  configStatuses: string[];
  auditResults: string[];
}): OperationsSummary => ({
  orgCount: input.orgStatuses.length,
  storeCount: input.storeStatuses.length,
  productCount: input.productStatuses.length,
  configCount: input.configStatuses.length,
  preparingStoreCount: input.storeStatuses.filter((value) => value === 'PREPARING').length,
  inactiveProductCount: input.productStatuses.filter((value) => value !== 'ACTIVE').length,
  deniedAuditCount: input.auditResults.filter((value) => value === 'DENIED' || value === 'FAILURE').length
});

/** Gate 6E 受控写操作的页面状态，不映射也不推演任何领域状态机。 */
export type OperationPageState =
  | 'IDLE'
  | 'LOADING'
  | 'READY'
  | 'EMPTY'
  | 'CONFIRMING'
  | 'SUBMITTING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'STALE'
  | 'UNKNOWN';

/** 二次确认快照；状态和版本必须来自 Owner 最近一次查询结果。 */
export interface OperationConfirmation {
  owner: string;
  objectId: string;
  currentState: string;
  currentVersion: number;
  action: string;
  impact: string;
  reason: string;
  idempotencyKey: string;
}

/**
 * 构建高风险操作的不可变确认快照。这里只做输入完整性检查，状态迁移是否合法仍由服务端 Owner 判断。
 */
export const buildOperationConfirmation = (input: OperationConfirmation): Readonly<OperationConfirmation> => {
  const normalized = {
    ...input,
    owner: input.owner.trim(),
    objectId: input.objectId.trim(),
    currentState: input.currentState.trim(),
    action: input.action.trim(),
    impact: input.impact.trim(),
    reason: input.reason.trim(),
    idempotencyKey: input.idempotencyKey.trim()
  };
  if (
    !normalized.owner ||
    !normalized.objectId ||
    !normalized.currentState ||
    !normalized.action ||
    !normalized.impact ||
    normalized.reason.length < 4 ||
    !Number.isSafeInteger(normalized.currentVersion) ||
    normalized.currentVersion < 0 ||
    !/^[0-9A-HJKMNP-TV-Z]{26}$/.test(normalized.idempotencyKey)
  ) {
    throw new Error('ADM-OP-001: 状态、版本、影响、原因和幂等键必须完整');
  }
  return Object.freeze(normalized);
};

/** 将逗号分隔的平台标识转换为安全整数；仅用于不超过 Number 安全范围的服务端字段。 */
export const parseSafePlatformIds = (value: string, limit = 500): number[] => {
  const ids = [
    ...new Set(
      value
        .split(/[,，\s]+/)
        .map((item) => item.trim())
        .filter(Boolean)
    )
  ];
  if (!ids.length || ids.length > limit || ids.some((item) => !/^[1-9]\d{0,15}$/.test(item) || !Number.isSafeInteger(Number(item)))) {
    throw new Error('ADM-INPUT-003: 标识列表为空、超限或超过前端安全整数范围');
  }
  return ids.map(Number);
};

/** 校验精确十进制文本；返回字符串，禁止前端转换为浮点数参与领域计算。 */
export const exactDecimal = (value: string, allowNegative = false): string => {
  const normalized = value.trim();
  const expression = allowNegative ? /^-?(?:0|[1-9]\d{0,12})(?:\.\d{1,6})?$/ : /^(?:0|[1-9]\d{0,12})(?:\.\d{1,6})?$/;
  if (!expression.test(normalized)) throw new Error('ADM-INPUT-004: 数量必须为最多六位小数的精确文本');
  return normalized;
};

/**
 * 创建单航班执行器。重复点击共享原 Promise；失败不会伪造成功，并允许操作者使用原幂等键显式重试。
 */
export const createSingleFlight = () => {
  let pending: Promise<unknown> | undefined;
  return <T>(work: () => Promise<T>): Promise<T> => {
    if (pending) return pending as Promise<T>;
    const current = work();
    pending = current;
    return current.finally(() => {
      if (pending === current) pending = undefined;
    });
  };
};
