import request from '@/utils/request';
import type { AxiosPromise } from 'axios';
import { LOT_INVENTORY_ENDPOINT, LOT_POLICY_ENDPOINT, lotUlid, trustedLotPayload } from './contract';
import type { LotPolicyView, LotView, PublishLotPolicyCommand } from './types';

export const publishLotPolicy = (data: PublishLotPolicyCommand, correlationId: string): AxiosPromise<LotPolicyView> =>
  request({
    url: LOT_POLICY_ENDPOINT,
    method: 'post',
    headers: { 'X-Correlation-ID': lotUlid(correlationId) },
    data: trustedLotPayload({ ...data, policyVersionId: lotUlid(data.policyVersionId) })
  });

export const getEffectiveLotPolicy = (storeId: number, skuId: number, effectiveAt?: string): AxiosPromise<LotPolicyView> =>
  request({ url: `${LOT_POLICY_ENDPOINT}/effective`, method: 'get', params: { storeId, skuId, effectiveAt } });

export const listLotExpiryAlerts = (params: {
  storeId: number;
  warehouseId: string;
  businessDate: string;
  limit?: number;
}): AxiosPromise<LotView[]> =>
  request({
    url: `${LOT_INVENTORY_ENDPOINT}/alerts`,
    method: 'get',
    params: { ...params, warehouseId: lotUlid(params.warehouseId), limit: params.limit ?? 100 }
  });
