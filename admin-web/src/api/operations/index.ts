import request from '@/utils/request';
import type { AxiosPromise } from 'axios';
import { newOperationCommandId, OPERATIONS_ENDPOINTS, ownerUlid, platformId, trustedOperationsPayload } from './contract';
import type {
  CostBalanceView,
  CostLedgerView,
  InventoryBalanceView,
  InventoryLedgerView,
  OwnerOperationView,
  PointsAccountView,
  ProcurementOrderCreateRequest,
  ProcurementOrderDetail,
  ProcurementReceiptDetail,
  PromotionRuleCreateRequest,
  ReleaseCreateRequest,
  ReleaseSummary,
  ResolvedMemberView,
  RolloutSummary,
  RuleVersionView,
  StocktakeCreateRequest,
  StocktakeDetail,
  TransferCreateRequest,
  TransferDetail
} from './types';

const post = <T>(url: string, data: unknown, headers?: Record<string, string>): AxiosPromise<T> =>
  request({ url, method: 'post', data: trustedOperationsPayload(data), headers });

export const getInventoryBalance = (warehouseId: string, skuId: string): AxiosPromise<InventoryBalanceView> =>
  request({
    url: `${OPERATIONS_ENDPOINTS.inventory}/balances/${ownerUlid(warehouseId, 'warehouseId')}/${platformId(skuId, 'skuId')}`,
    method: 'get'
  });

export const getInventoryLedger = (warehouseId: string, skuId: string): AxiosPromise<InventoryLedgerView[]> =>
  request({ url: `${OPERATIONS_ENDPOINTS.inventory}/ledgers/${ownerUlid(warehouseId, 'warehouseId')}/${platformId(skuId, 'skuId')}`, method: 'get' });

export const rebuildInventoryBalance = (warehouseId: string, skuId: string, correlationId: string): AxiosPromise<OwnerOperationView> =>
  post(`${OPERATIONS_ENDPOINTS.inventory}/balances/${ownerUlid(warehouseId, 'warehouseId')}/${platformId(skuId, 'skuId')}/rebuild`, {
    correlationId
  });

export const getCostBalance = (warehouseId: string, skuId: string): AxiosPromise<CostBalanceView> =>
  request({
    url: `${OPERATIONS_ENDPOINTS.costing}/cost-balances/${ownerUlid(warehouseId, 'warehouseId')}/${platformId(skuId, 'skuId')}`,
    method: 'get'
  });

export const getCostLedger = (warehouseId: string, skuId: string, afterSequence = 0, limit = 100): AxiosPromise<CostLedgerView[]> =>
  request({
    url: `${OPERATIONS_ENDPOINTS.costing}/cost-ledgers/${ownerUlid(warehouseId, 'warehouseId')}/${platformId(skuId, 'skuId')}`,
    method: 'get',
    params: { afterSequence, limit }
  });

export const rebuildCostBalance = (warehouseId: string, skuId: string, rebuildId: string, correlationId: string): AxiosPromise<OwnerOperationView> =>
  post(`${OPERATIONS_ENDPOINTS.costing}/cost-balances/${ownerUlid(warehouseId, 'warehouseId')}/${platformId(skuId, 'skuId')}/rebuild`, {
    rebuildId: ownerUlid(rebuildId, 'rebuildId'),
    correlationId
  });

export const createStocktake = (data: StocktakeCreateRequest): AxiosPromise<StocktakeDetail> => post(OPERATIONS_ENDPOINTS.stocktakes, data);
export const getStocktake = (stocktakeId: string): AxiosPromise<StocktakeDetail> =>
  request({ url: `${OPERATIONS_ENDPOINTS.stocktakes}/${ownerUlid(stocktakeId, 'stocktakeId')}`, method: 'get' });
export const recordStocktakeCount = (
  stocktakeId: string,
  lineId: string,
  data: { countId: string; countedQuantity: string; deviceId: string; reason?: string; correlationId: string }
): AxiosPromise<StocktakeDetail> =>
  post(`${OPERATIONS_ENDPOINTS.stocktakes}/${ownerUlid(stocktakeId, 'stocktakeId')}/lines/${ownerUlid(lineId, 'lineId')}/counts`, data);
export const transitionStocktake = (
  stocktakeId: string,
  action: 'submit' | 'review' | 'approve',
  data: Record<string, unknown>
): AxiosPromise<StocktakeDetail> => post(`${OPERATIONS_ENDPOINTS.stocktakes}/${ownerUlid(stocktakeId, 'stocktakeId')}/${action}`, data);

export const createSupplier = (data: { supplierId: string; code: string; name: string; correlationId: string }): AxiosPromise<OwnerOperationView> =>
  post(`${OPERATIONS_ENDPOINTS.procurement}/suppliers`, data);
export const changeSupplierState = (
  supplierId: string,
  data: { state: 'ACTIVE' | 'SUSPENDED' | 'BLOCKED'; reason: string; correlationId: string }
): AxiosPromise<OwnerOperationView> => post(`${OPERATIONS_ENDPOINTS.procurement}/suppliers/${ownerUlid(supplierId, 'supplierId')}/state`, data);
export const createProcurementOrder = (data: ProcurementOrderCreateRequest): AxiosPromise<ProcurementOrderDetail> =>
  post(`${OPERATIONS_ENDPOINTS.procurement}/orders`, data);
export const getProcurementOrder = (orderId: string): AxiosPromise<ProcurementOrderDetail> =>
  request({ url: `${OPERATIONS_ENDPOINTS.procurement}/orders/${ownerUlid(orderId, 'orderId')}`, method: 'get' });
export const transitionProcurementOrder = (
  orderId: string,
  action: 'submit' | 'approve' | 'close',
  data: { correlationId: string; reason?: string }
): AxiosPromise<ProcurementOrderDetail> => post(`${OPERATIONS_ENDPOINTS.procurement}/orders/${ownerUlid(orderId, 'orderId')}/${action}`, data);
export const createProcurementReceipt = (
  orderId: string,
  data: { receiptId: string; lines: Array<{ receiptLineId: string; orderLineId: string; receivedQuantity: string }>; correlationId: string }
): AxiosPromise<ProcurementReceiptDetail> => post(`${OPERATIONS_ENDPOINTS.procurement}/orders/${ownerUlid(orderId, 'orderId')}/receipts`, data);
export const getProcurementReceipt = (receiptId: string): AxiosPromise<ProcurementReceiptDetail> =>
  request({ url: `${OPERATIONS_ENDPOINTS.procurement}/receipts/${ownerUlid(receiptId, 'receiptId')}`, method: 'get' });
export const confirmProcurementReceipt = (receiptId: string, eventId: string, correlationId: string): AxiosPromise<ProcurementReceiptDetail> =>
  post(`${OPERATIONS_ENDPOINTS.procurement}/receipts/${ownerUlid(receiptId, 'receiptId')}/confirm`, { eventId, correlationId });
export const createProcurementReturn = (
  receiptId: string,
  data: {
    purchaseReturnId: string;
    lines: Array<{ returnLineId: string; receiptLineId: string; returnQuantity: string }>;
    reason: string;
    correlationId: string;
  }
): AxiosPromise<OwnerOperationView> => post(`${OPERATIONS_ENDPOINTS.procurement}/receipts/${ownerUlid(receiptId, 'receiptId')}/returns`, data);
export const transitionProcurementReturn = (
  purchaseReturnId: string,
  action: 'submit' | 'approve',
  data: { correlationId: string; eventId?: string }
): AxiosPromise<OwnerOperationView> =>
  post(`${OPERATIONS_ENDPOINTS.procurement}/returns/${ownerUlid(purchaseReturnId, 'purchaseReturnId')}/${action}`, data);

export const createTransfer = (data: TransferCreateRequest): AxiosPromise<TransferDetail> => post(OPERATIONS_ENDPOINTS.transfers, data);
export const getTransfer = (transferId: string): AxiosPromise<TransferDetail> =>
  request({ url: `${OPERATIONS_ENDPOINTS.transfers}/${ownerUlid(transferId, 'transferId')}`, method: 'get' });
export const transitionTransfer = (
  transferId: string,
  action: 'submit' | 'approve' | 'cancel',
  data: { commandId: string; expectedVersion: number; reason: string; correlationId: string }
): AxiosPromise<TransferDetail> => post(`${OPERATIONS_ENDPOINTS.transfers}/${ownerUlid(transferId, 'transferId')}/${action}`, data);
export const dispatchTransfer = (
  transferId: string,
  data: { dispatchId: string; eventId: string; expectedVersion: number; correlationId: string }
): AxiosPromise<TransferDetail> => post(`${OPERATIONS_ENDPOINTS.transfers}/${ownerUlid(transferId, 'transferId')}/dispatch`, data);
export const receiveTransfer = (
  transferId: string,
  data: {
    receiptId: string;
    eventId: string;
    expectedVersion: number;
    finalReceipt: boolean;
    lines: Array<{ receiptLineId: string; transferLineId: string; receivedQuantity: string }>;
    correlationId: string;
  }
): AxiosPromise<TransferDetail> => post(`${OPERATIONS_ENDPOINTS.transfers}/${ownerUlid(transferId, 'transferId')}/receipts`, data);

export const createPromotionRule = (data: PromotionRuleCreateRequest): AxiosPromise<RuleVersionView> =>
  post(`${OPERATIONS_ENDPOINTS.promotions}/rules`, data);
export const transitionPromotionRule = (
  ruleId: string,
  versionId: string,
  action: 'validate' | 'approve' | 'publish' | 'pause' | 'reject' | 'retire',
  data: { commandId: string; expectedVersion: number; reason: string; correlationId: string }
): AxiosPromise<RuleVersionView> =>
  post(`${OPERATIONS_ENDPOINTS.promotions}/rules/${ownerUlid(ruleId, 'ruleId')}/versions/${ownerUlid(versionId, 'versionId')}/${action}`, data);

export const resolveMember = (data: { storeId: number; identityType: string; identityValue: string }): AxiosPromise<ResolvedMemberView> =>
  post(`${OPERATIONS_ENDPOINTS.members}/resolve`, data);
export const createMember = (data: {
  commandId: string;
  memberId: string;
  identityId: string;
  identityType: string;
  identityValue: string;
  correlationId: string;
}): AxiosPromise<OwnerOperationView> => post(OPERATIONS_ENDPOINTS.members, data);
export const recordMemberConsent = (
  memberId: string,
  data: {
    commandId: string;
    consentId: string;
    purposeCode: string;
    policyVersion: string;
    state: 'GRANTED' | 'REVOKED';
    evidenceSha256: string;
    correlationId: string;
  }
): AxiosPromise<OwnerOperationView> => post(`${OPERATIONS_ENDPOINTS.members}/${ownerUlid(memberId, 'memberId')}/consents`, data);
export const createPrivacyRequest = (
  memberId: string,
  data: { commandId: string; requestId: string; requestType: 'ACCESS' | 'EXPORT' | 'CORRECT' | 'DELETE'; reason: string; correlationId: string }
): AxiosPromise<OwnerOperationView> => post(`${OPERATIONS_ENDPOINTS.members}/${ownerUlid(memberId, 'memberId')}/privacy-requests`, data);
export const transitionPrivacyRequest = (
  requestId: string,
  data: { commandId: string; toState: string; expectedVersion: number; reason: string; correlationId: string }
): AxiosPromise<OwnerOperationView> => post(`${OPERATIONS_ENDPOINTS.privacyRequests}/${ownerUlid(requestId, 'requestId')}/transitions`, data);
export const getMemberPoints = (memberId: string, storeId: string): AxiosPromise<PointsAccountView> =>
  request({
    url: `${OPERATIONS_ENDPOINTS.members}/${ownerUlid(memberId, 'memberId')}/points`,
    method: 'get',
    params: { storeId: platformId(storeId, 'storeId') }
  });
export const adjustMemberPoints = (
  memberId: string,
  data: {
    commandId: string;
    ledgerId: string;
    storeId: number;
    signedAmount: string;
    policyVersion: string;
    reason: string;
    approvalUserId: number;
    approvalRef: string;
    occurredAt: string;
    correlationId: string;
  }
): AxiosPromise<OwnerOperationView> => post(`${OPERATIONS_ENDPOINTS.members}/${ownerUlid(memberId, 'memberId')}/points/adjustments`, data);

export const createRelease = (data: ReleaseCreateRequest, idempotencyKey = newOperationCommandId()): AxiosPromise<ReleaseSummary> =>
  post(OPERATIONS_ENDPOINTS.releases, data, { 'X-Idempotency-Key': idempotencyKey });
export const getRelease = (releaseId: string): AxiosPromise<ReleaseSummary> =>
  request({ url: `${OPERATIONS_ENDPOINTS.releases}/${ownerUlid(releaseId, 'releaseId')}`, method: 'get' });
export const transitionRelease = (releaseId: string, action: 'verify' | 'stage' | 'revoke', idempotencyKey: string): AxiosPromise<ReleaseSummary> =>
  post(`${OPERATIONS_ENDPOINTS.releases}/${ownerUlid(releaseId, 'releaseId')}/${action}`, undefined, { 'X-Idempotency-Key': idempotencyKey });
export const createRollout = (
  releaseId: string,
  data: { targetStoreIds: number[]; canaryPercent: number },
  idempotencyKey: string
): AxiosPromise<RolloutSummary> =>
  post(`${OPERATIONS_ENDPOINTS.releases}/${ownerUlid(releaseId, 'releaseId')}/rollouts`, data, { 'X-Idempotency-Key': idempotencyKey });
export const transitionRollout = (
  rolloutId: string,
  action: 'start-canary' | 'expand' | 'pause' | 'complete',
  idempotencyKey: string
): AxiosPromise<RolloutSummary> =>
  post(`${OPERATIONS_ENDPOINTS.releases}/rollouts/${ownerUlid(rolloutId, 'rolloutId')}/${action}`, undefined, {
    'X-Idempotency-Key': idempotencyKey
  });

export { OPERATIONS_ENDPOINTS, newOperationCommandId } from './contract';
