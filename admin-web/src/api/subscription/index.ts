import request from '@/utils/request';
import type { AxiosPromise } from 'axios';
import { SUBSCRIPTION_ENDPOINT, subscriptionTenant, subscriptionUlid, trustedSubscriptionPayload } from './contract';
import type { SubscriptionDetail, SubscriptionTermInput } from './types';

export interface SubscriptionIdentity {
  idempotencyKey: string;
  correlationId: string;
}
const headers = (i: SubscriptionIdentity) => ({ 'Idempotency-Key': i.idempotencyKey, 'X-Correlation-ID': i.correlationId });
export const createSubscription = (
  targetTenantId: string,
  data: SubscriptionTermInput & { degradationPolicyVersion: string },
  i: SubscriptionIdentity
): AxiosPromise<SubscriptionDetail> =>
  request({
    url: `${SUBSCRIPTION_ENDPOINT}/tenants/${subscriptionTenant(targetTenantId)}`,
    method: 'post',
    headers: headers(i),
    data: trustedSubscriptionPayload(data)
  });
export const getSubscription = (id: string): AxiosPromise<SubscriptionDetail> =>
  request({ url: `${SUBSCRIPTION_ENDPOINT}/${subscriptionUlid(id)}`, method: 'get' });
export const getCurrentSubscription = (): AxiosPromise<SubscriptionDetail> => request({ url: `${SUBSCRIPTION_ENDPOINT}/current`, method: 'get' });
const action = (id: string, name: string, i: SubscriptionIdentity, data?: object): AxiosPromise<SubscriptionDetail> =>
  request({
    url: `${SUBSCRIPTION_ENDPOINT}/${subscriptionUlid(id)}/${name}`,
    method: 'post',
    headers: headers(i),
    data: data ? trustedSubscriptionPayload(data) : undefined
  });
export const activateSubscription = (id: string, i: SubscriptionIdentity) => action(id, 'activate', i);
export const renewSubscription = (id: string, data: SubscriptionTermInput, i: SubscriptionIdentity) => action(id, 'renew', i, data);
export const suspendSubscription = (id: string, reason: string, i: SubscriptionIdentity) => action(id, 'suspend', i, { reason });
export const restoreSubscription = (id: string, data: SubscriptionTermInput, i: SubscriptionIdentity) => action(id, 'restore', i, data);
export const requestSubscriptionTermination = (id: string, reason: string, i: SubscriptionIdentity) =>
  action(id, 'request-termination', i, { reason });
export const terminateSubscription = (id: string, reason: string, i: SubscriptionIdentity) => action(id, 'terminate', i, { reason });
export const runSubscriptionExpiryScan = (
  runnerId: string
): AxiosPromise<{ runnerId: string; inspected: number; transitioned: number; scannedAt: string }> =>
  request({ url: `${SUBSCRIPTION_ENDPOINT}/jobs/expiry-scan`, method: 'post', headers: { 'X-Correlation-ID': runnerId } });
