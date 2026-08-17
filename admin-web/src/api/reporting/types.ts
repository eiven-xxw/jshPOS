export type Id64 = string | number;
export type ReportType = 'SALES_DAILY' | 'INVENTORY_COST_DAILY';
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

export interface ReportQuery {
  fromDate: string;
  toDate: string;
  storeId: Id64;
  terminalId?: string;
  cashierId?: Id64;
  warehouseId?: string;
  skuId?: Id64;
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
