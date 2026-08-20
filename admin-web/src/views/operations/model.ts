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
