export type Id64 = string | number;
export type ReportType = 'SALES_DAILY' | 'INVENTORY_COST_DAILY' | 'PAYMENT_RECONCILIATION';
export type ProjectionStatus = 'CURRENT' | 'INCOMPLETE';
export type ExportState = 'REQUESTED' | 'APPROVED' | 'REJECTED' | 'GENERATING' | 'READY' | 'FAILED' | 'EXPIRED';

export interface SalesDailyVO {
  businessDate: string;
  orgId: Id64;
  storeId: Id64;
  terminalId: string;
  cashierId: Id64;
  currency: 'CNY';
  orderCount: number;
  cancelledOrderCount: number;
  returnCount: number;
  grossMinor: number;
  discountMinor: number;
  surchargeMinor: number;
  receivableMinor: number;
  refundMinor: number;
  cashReceivedMinor: number;
  cashRefundedMinor: number;
  shiftDifferenceMinor: number;
  promotionSnapshotCount: number;
  projectionStatus: ProjectionStatus;
}

export interface SalesDailyPageVO {
  items: SalesDailyVO[];
  nextCursor?: string;
  hasMore: boolean;
  projectionVersion?: string;
}

export interface InventoryCostDailyVO {
  businessDate: string;
  orgId: Id64;
  storeId: Id64;
  warehouseId: string;
  skuId: Id64;
  currency: 'CNY';
  onHandDelta: string;
  availableDelta: string;
  reservedDelta: string;
  ledgerQuantityDelta: string;
  purchaseQuantityDelta: string;
  stocktakeQuantityDelta: string;
  transferQuantityDelta: string;
  inventoryValueDeltaMinor: string;
  cogsDeltaMinor: string;
  purchaseCostDeltaMinor: string;
  stocktakeCostDeltaMinor: string;
  transferCostDeltaMinor: string;
  projectionStatus: ProjectionStatus;
}

export interface PaymentReconciliationVO {
  reconciliationId: string;
  reconciliationKey: string;
  factType: 'PAYMENT' | 'REFUND';
  businessDate: string;
  storeId: Id64;
  terminalId: string;
  currency: 'CNY';
  internalAmountMinor?: number;
  billAmountMinor?: number;
  internalStatus?: 'SUCCEEDED' | 'FAILED' | 'UNKNOWN';
  billStatus?: 'SUCCEEDED' | 'FAILED' | 'UNKNOWN';
  internalBusinessDate?: string;
  billBusinessDate?: string;
  differenceType:
    | 'MATCHED'
    | 'MISSING_BILL'
    | 'MISSING_INTERNAL'
    | 'AMOUNT_MISMATCH'
    | 'CURRENCY_MISMATCH'
    | 'STATUS_MISMATCH'
    | 'BUSINESS_DATE_MISMATCH';
  handlingState: 'MATCHED' | 'OPEN' | 'ASSIGNED' | 'RESOLVED' | 'IGNORED';
  handlerId?: Id64;
  version: number;
}

export interface PaymentReconciliationAuditVO {
  auditId: string;
  reconciliationId: string;
  actionType: 'SYSTEM_CLASSIFIED' | 'MANUAL_TRANSITION';
  fromDifferenceType?: PaymentReconciliationVO['differenceType'];
  toDifferenceType: PaymentReconciliationVO['differenceType'];
  fromHandlingState?: PaymentReconciliationVO['handlingState'];
  toHandlingState: PaymentReconciliationVO['handlingState'];
  operatorId: Id64;
  reasonSha256: string;
  correlationId: string;
  occurredAt: string;
}

export interface ReportQuery {
  fromDate: string;
  toDate: string;
  storeId: Id64;
  terminalId?: string;
  cashierId?: Id64;
  warehouseId?: string;
  skuId?: Id64;
  differenceType?: PaymentReconciliationVO['differenceType'];
  handlingState?: PaymentReconciliationVO['handlingState'];
}

export interface SalesPageQuery extends ReportQuery {
  cursor?: string;
  limit?: number;
}

export interface ExportRequest {
  exportId: string;
  reportType: ReportType;
  fromDate: string;
  toDate: string;
  storeIds: Id64[];
  fields: string[];
  correlationId: string;
}

export interface ExportVO extends ExportRequest {
  state: ExportState;
  approvalRequired: boolean;
  requestedBy: Id64;
  approvedBy?: Id64;
  estimatedRows: number;
  artifactSha256?: string;
  expiresAt?: string;
  version: number;
}

export interface DownloadTokenVO {
  exportId: string;
  token: string;
  expiresAt: string;
}
