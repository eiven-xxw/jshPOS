import request from '@/utils/request';
import type { AxiosPromise } from 'axios';
import { SAAS_ENDPOINT, saasTenant, saasUlid, trustedSaasPayload } from './contract';
import type { EntitlementVersion, SaasApplicationDetail, SaasPlan, TenantEntitlement } from './types';

export interface SaasIdentity {
  idempotencyKey: string;
  correlationId: string;
}
const headers = (identity: SaasIdentity) => ({ 'Idempotency-Key': identity.idempotencyKey, 'X-Correlation-ID': identity.correlationId });

export const createSaasApplication = (
  data: { applicationCode: string; companyName: string; industry: string; planId: number },
  i: SaasIdentity
): AxiosPromise<SaasApplicationDetail> =>
  request({ url: `${SAAS_ENDPOINT}/applications`, method: 'post', headers: headers(i), data: trustedSaasPayload(data) });
export const getSaasApplication = (id: string): AxiosPromise<SaasApplicationDetail> =>
  request({ url: `${SAAS_ENDPOINT}/applications/${saasUlid(id)}`, method: 'get' });
const appAction = (id: string, action: string, i: SaasIdentity, data?: Record<string, unknown>): AxiosPromise<SaasApplicationDetail> =>
  request({
    url: `${SAAS_ENDPOINT}/applications/${saasUlid(id)}/${action}`,
    method: 'post',
    headers: headers(i),
    data: data ? trustedSaasPayload(data) : undefined
  });
export const preflightSaasApplication = (id: string, i: SaasIdentity) => appAction(id, 'preflight', i);
export const approveSaasApplication = (id: string, reason: string, i: SaasIdentity) => appAction(id, 'approve', i, { reason });
export const provisionSaasApplication = (
  id: string,
  data: { contactName: string; contactPhone: string; bootstrapUsername: string; bootstrapPassword: string },
  i: SaasIdentity
) => appAction(id, 'provision', i, data);
export const initializeSaasApplication = (id: string, i: SaasIdentity) => appAction(id, 'initialize', i);
export const activateSaasApplication = (id: string, i: SaasIdentity) => appAction(id, 'activate', i);
export const createSaasPlan = (
  data: { planCode: string; planName: string; platformPackageId: number; accountLimit: number },
  i: SaasIdentity
): AxiosPromise<SaasPlan> => request({ url: `${SAAS_ENDPOINT}/plans`, method: 'post', headers: headers(i), data: trustedSaasPayload(data) });
export const createEntitlementVersion = (planId: number, data: Record<string, unknown>, i: SaasIdentity): AxiosPromise<EntitlementVersion> =>
  request({ url: `${SAAS_ENDPOINT}/plans/${planId}/versions`, method: 'post', headers: headers(i), data: trustedSaasPayload(data) });
export const advanceEntitlementVersion = (
  id: string,
  action: 'validate' | 'approve' | 'publish' | 'activate',
  i: SaasIdentity
): AxiosPromise<EntitlementVersion> =>
  request({ url: `${SAAS_ENDPOINT}/entitlements/${saasUlid(id)}/${action}`, method: 'post', headers: headers(i) });
export const changeTenantLifecycle = (
  tenantId: string,
  action: 'suspend' | 'deactivate' | 'restore' | 'request-termination' | 'terminate-logical',
  reason: string,
  i: SaasIdentity
): AxiosPromise<TenantEntitlement> =>
  request({
    url: `${SAAS_ENDPOINT}/tenants/${saasTenant(tenantId)}/${action}`,
    method: 'post',
    headers: headers(i),
    data: trustedSaasPayload({ reason })
  });
