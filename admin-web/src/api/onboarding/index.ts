import request from '@/utils/request';
import type { AxiosPromise } from 'axios';
import { ONBOARDING_ENDPOINT, onboardingUlid, trustedOnboardingPayload } from './contract';
import type { OnboardingPlanDetail } from './types';

export interface OnboardingIdentity {
  idempotencyKey: string;
  correlationId: string;
}

const headers = (identity: OnboardingIdentity) => ({
  'Idempotency-Key': identity.idempotencyKey,
  'X-Correlation-ID': identity.correlationId
});

export const createOnboardingPlan = (
  data: {
    sourceStoreId?: number;
    targetStoreId: number;
    templateId: number;
    templateVersionId: number;
  },
  identity: OnboardingIdentity
): AxiosPromise<OnboardingPlanDetail> =>
  request({
    url: ONBOARDING_ENDPOINT,
    method: 'post',
    headers: headers(identity),
    data: trustedOnboardingPayload(data)
  });

export const getOnboardingPlan = (planId: string): AxiosPromise<OnboardingPlanDetail> =>
  request({
    url: `${ONBOARDING_ENDPOINT}/${onboardingUlid(planId)}`,
    method: 'get'
  });

const action = (planId: string, name: string, identity: OnboardingIdentity, data?: Record<string, unknown>): AxiosPromise<OnboardingPlanDetail> =>
  request({
    url: `${ONBOARDING_ENDPOINT}/${onboardingUlid(planId)}/${name}`,
    method: 'post',
    headers: headers(identity),
    data: data ? trustedOnboardingPayload(data) : undefined
  });

export const preflightOnboardingPlan = (id: string, identity: OnboardingIdentity) => action(id, 'preflight', identity);
export const approveOnboardingPlan = (id: string, reason: string, identity: OnboardingIdentity) => action(id, 'approve', identity, { reason });
export const applyOnboardingPlan = (id: string, identity: OnboardingIdentity) => action(id, 'apply', identity);
export const checkOnboardingPlan = (id: string, identity: OnboardingIdentity) => action(id, 'checks', identity);
export const openOnboardingStore = (id: string, reason: string, identity: OnboardingIdentity) => action(id, 'open', identity, { reason });
export const cancelOnboardingPlan = (id: string, reason: string, identity: OnboardingIdentity) => action(id, 'cancel', identity, { reason });
