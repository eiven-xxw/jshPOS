export type ExpiryBasis = 'PRODUCTION_DATE' | 'RECEIVED_DATE' | 'EXPLICIT_EXPIRY_DATE';
export type LotExpiryStatus = 'AVAILABLE' | 'NEAR_EXPIRY' | 'EXPIRED' | 'DEPLETED' | 'BLOCKED';

export interface LotPolicyView {
  policyVersionId: string;
  storeId: number;
  skuId: number;
  enabled: boolean;
  expiryBasis: ExpiryBasis;
  shelfLifeDays?: number;
  nearExpiryDays: number;
  industry: 'COMMUNITY_SUPERMARKET';
  templateVersionId: number;
  effectiveFrom: string;
  contentSha256: string;
  state: 'PUBLISHED';
}

export interface LotView {
  lotId: string;
  storeId: number;
  warehouseId: string;
  skuId: number;
  baseUnitId: number;
  supplierLotCode?: string;
  internalLotCode: string;
  productionDate?: string;
  receivedDate: string;
  expiryDate: string;
  policyVersionId: string;
  nearExpiryDays: number;
  onHandQuantity: string;
  lastLedgerSequence: number;
  expiryStatus: LotExpiryStatus;
  updatedAt: string;
}

export interface PublishLotPolicyCommand {
  policyVersionId: string;
  storeId: number;
  skuId: number;
  enabled: boolean;
  expiryBasis: ExpiryBasis;
  shelfLifeDays?: number;
  nearExpiryDays: number;
  effectiveFrom: string;
}
