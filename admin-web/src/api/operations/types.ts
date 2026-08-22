export type Id64 = string | number;
export type ExactDecimal = string;

/** 所有后台操作结果只展示服务端返回的状态、版本和关联标识，不在前端推演领域状态。 */
export interface OwnerOperationView {
  status?: string;
  state?: string;
  version?: number | string;
  recordVersion?: number;
  correlationId?: string;
  [key: string]: unknown;
}

export interface InventoryBalanceView extends OwnerOperationView {
  dimensionKey: string;
  warehouseId: string;
  skuId: Id64;
  stockStatus: string;
  onHandQuantity: ExactDecimal;
  reservedQuantity: ExactDecimal;
  frozenQuantity: ExactDecimal;
  safetyStockQuantity: ExactDecimal;
  lastLedgerSequence: number;
  recordVersion: number;
}

export interface InventoryLedgerView extends OwnerOperationView {
  ledgerId: string;
  ledgerSequence: number;
  movementType: string;
  quantityBefore: ExactDecimal;
  quantityDelta: ExactDecimal;
  quantityAfter: ExactDecimal;
  sourceType: string;
  sourceId: string;
  occurredAt: string;
}

export interface CostBalanceView extends OwnerOperationView {
  costDimensionKey: string;
  warehouseId: string;
  storeId: Id64;
  skuId: Id64;
  currencyCode: string;
  costQuantity: ExactDecimal;
  costAmountMinor: ExactDecimal;
  averageUnitCostMinor: ExactDecimal;
  lastCostLedgerSequence: number;
  recordVersion: number;
}

export interface CostLedgerView extends OwnerOperationView {
  costLedgerId: string;
  costLedgerSequence: number;
  movementType: string;
  quantityDelta: ExactDecimal;
  costAmountDeltaMinor: ExactDecimal;
  unitCostMinor: ExactDecimal;
  costEstimated: boolean;
  sourceType: string;
  sourceId: string;
  occurredAt: string;
}

export interface StocktakeLineView {
  lineId: string;
  skuId: Id64;
  snapshotQuantity: ExactDecimal;
  countedQuantity?: ExactDecimal;
  varianceQuantity?: ExactDecimal;
  countRevision: number;
}

export interface StocktakeDetail extends OwnerOperationView {
  head: {
    stocktakeId: string;
    storeId: Id64;
    warehouseId: string;
    status: string;
    blindCount: boolean;
    version: number;
    correlationId: string;
  };
  lines: StocktakeLineView[];
}

export interface ProcurementOrderDetail extends OwnerOperationView {
  head: {
    orderId: string;
    supplierId: string;
    storeId: Id64;
    warehouseId: string;
    status: string;
    expectedDate: string;
    version: number;
  };
  lines: Array<{
    orderLineId: string;
    skuId: Id64;
    orderedQuantity: ExactDecimal;
    receivedQuantity: ExactDecimal;
    unitPriceMinor: number;
  }>;
}

export interface ProcurementReceiptDetail extends OwnerOperationView {
  head: {
    receiptId: string;
    orderId: string;
    status: string;
    version: number;
    correlationId: string;
  };
  lines: Array<{
    receiptLineId: string;
    orderLineId: string;
    skuId: Id64;
    receivedQuantity: ExactDecimal;
    returnedQuantity: ExactDecimal;
  }>;
}

/** 服务端冻结并解释的补货建议；前端不得由库存值自行推导建议数量。 */
export interface ReplenishmentSuggestion extends OwnerOperationView {
  suggestionId: string;
  policyVersionId: string;
  storeId: Id64;
  warehouseId: string;
  skuId: Id64;
  skuCode: string;
  supplierId: string;
  availableQuantity: ExactDecimal;
  confirmedInTransitQuantity: ExactDecimal;
  effectiveQuantity: ExactDecimal;
  minimumBaseQuantity: ExactDecimal;
  maximumBaseQuantity: ExactDecimal;
  requiredBaseQuantity: ExactDecimal;
  suggestedPurchaseQuantity: ExactDecimal;
  reasonCode: string;
  state: string;
  purchaseOrderId?: string;
  failureCode?: string;
  version: number;
}

export interface ReplenishmentPolicy extends OwnerOperationView {
  policyVersionId: string;
  storeId: Id64;
  warehouseId: string;
  versionNo: number;
  state: string;
  effectiveFrom: string;
  contentSha256?: string;
  version: number;
}

export interface ReplenishmentPolicyCreateRequest {
  policyVersionId: string;
  storeId: string;
  warehouseId: string;
  versionNo: number;
  effectiveFrom: string;
  items: Array<{
    policyItemId: string;
    skuId: string;
    purchaseUnitId: string;
    supplierId: string;
    minimumBaseQuantity: ExactDecimal;
    maximumBaseQuantity: ExactDecimal;
    minimumOrderQuantity: ExactDecimal;
    orderMultiple: ExactDecimal;
    includeConfirmedInTransit: boolean;
    unitPriceMinor: string;
    taxRateBps: number;
  }>;
  idempotencyKey: string;
  correlationId: string;
}

export interface TransferDetail extends OwnerOperationView {
  head: {
    transferId: string;
    sourceStoreId: Id64;
    sourceWarehouseId: string;
    destinationStoreId: Id64;
    destinationWarehouseId: string;
    status: string;
    version: number;
  };
  lines: Array<{
    transferLineId: string;
    skuId: Id64;
    requestedQuantity: ExactDecimal;
    dispatchedQuantity: ExactDecimal;
    receivedQuantity: ExactDecimal;
    differenceQuantity: ExactDecimal;
  }>;
}

export interface RuleVersionView extends OwnerOperationView {
  ruleId: string;
  ruleVersionId: string;
  ruleCode: string;
  ruleName: string;
  versionNo: number;
  state: string;
  version: number;
  contentSha256: string;
}

export interface ResolvedMemberView extends OwnerOperationView {
  member: { memberId: string; state: string; displayName: string; version: number };
  matchedIdentity: { identityId: string; identityType: string; maskedValue: string; state: string };
}

export interface PointsAccountView extends OwnerOperationView {
  memberId: string;
  availablePoints: ExactDecimal;
  frozenPoints: ExactDecimal;
  debtPoints: ExactDecimal;
  version: number;
  lastLedgerId?: string;
}

export interface ReleaseSummary extends OwnerOperationView {
  releaseId: string;
  artifactType: string;
  version: string;
  channel: string;
  state: string;
  manifestSha256: string;
  buildCommit: string;
  sbomSha256: string;
  targetStoreCount: number;
}

export interface RolloutSummary extends OwnerOperationView {
  rolloutId: string;
  releaseId: string;
  state: string;
  canaryPercent: number;
  targetStoreCount: number;
}

export interface StocktakeCreateRequest {
  stocktakeId: string;
  warehouseId: string;
  skuIds: number[];
  blindCount: boolean;
  recountThreshold: ExactDecimal;
  correlationId: string;
}

export interface ProcurementOrderCreateRequest {
  orderId: string;
  supplierId: string;
  storeId: string;
  warehouseId: string;
  expectedDate: string;
  overReceiptToleranceBps: number;
  lines: Array<{
    orderLineId: string;
    skuId: string;
    unitId: string;
    orderedQuantity: ExactDecimal;
    unitPriceMinor: string;
    taxRateBps: number;
  }>;
  correlationId: string;
}

export interface TransferCreateRequest {
  transferId: string;
  sourceStoreId: string;
  sourceWarehouseId: string;
  destinationStoreId: string;
  destinationWarehouseId: string;
  lines: Array<{ transferLineId: string; skuId: string; unitId: string; requestedQuantity: ExactDecimal }>;
  reason: string;
  correlationId: string;
}

export interface PromotionRuleCreateRequest {
  commandId: string;
  ruleId: string;
  ruleVersionId: string;
  ruleCode: string;
  name: string;
  definition: {
    ruleType: string;
    priority: number;
    stackMode: string;
    exclusiveGroup?: string;
    effectiveFrom: string;
    effectiveTo?: string;
    scope: {
      skuIds: string[];
      categoryIds: string[];
      brandIds: string[];
      storeIds: string[];
      channels: string[];
      businessDays: number[];
    };
    benefit: {
      amountMinor?: number;
      discountRate?: string;
      nth?: number;
      thresholdMinor?: number;
      thresholdQuantity?: string;
      bundlePriceMinor?: number;
      bundleComponents: Array<{ skuId: string; quantity: string }>;
    };
  };
  correlationId: string;
}

export interface ReleaseCreateRequest {
  artifactType: 'SERVER' | 'WEB' | 'MYSQL_SCHEMA' | 'SQLITE_SCHEMA' | 'TEMPLATE_PACKAGE' | 'DATA_PACKAGE' | 'APK';
  version: string;
  channel: 'INTERNAL' | 'CANARY' | 'STABLE' | 'EMERGENCY';
  objectKey: string;
  artifactSha256: string;
  signatureBase64: string;
  keyVersion: string;
  buildCommit: string;
  sbomSha256: string;
  compatibility: {
    minAppVersion: string;
    maxAppVersion: string;
    minProtocolVersion: string;
    maxProtocolVersion: string;
    minSchemaVersion: string;
    maxSchemaVersion: string;
    minSystemVersion: string;
    maxSystemVersion: string;
    requiredCapabilitySha256: string;
  };
  targetStoreIds: number[];
}
