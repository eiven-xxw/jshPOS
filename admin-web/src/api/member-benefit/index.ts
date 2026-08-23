import request from '@/utils/request';
import type { AxiosPromise } from 'axios';
import { benefitMinor, benefitSha256, benefitUlid, MEMBER_BENEFIT_ENDPOINTS, trustedMemberBenefitPayload } from './contract';
import type { BenefitPolicyVersionVO, MemberBenefitPackageVO, MemberPriceVersionVO } from './types';

const post = <T>(url: string, data: unknown): AxiosPromise<T> => request({ url, method: 'post', data: trustedMemberBenefitPayload(data) });

export const createBenefitPolicy = (data: {
  commandId: string;
  policyId: string;
  versionId: string;
  policyCode: string;
  displayName: string;
  levelRules: Array<{ levelCode: string; memberPriceEligible: boolean; stackingAllowed: boolean }>;
  storeIds: number[];
  correlationId: string;
}): AxiosPromise<BenefitPolicyVersionVO> => post(MEMBER_BENEFIT_ENDPOINTS.policies, data);

export const transitionBenefitPolicy = (
  policyId: string,
  versionId: string,
  action: 'validate' | 'approve' | 'publish' | 'pause' | 'resume' | 'revoke',
  data: {
    commandId: string;
    contentSha256: string;
    effectiveAt?: string;
    expiresAt?: string;
    reasonCode?: string;
    reason?: string;
    correlationId: string;
  }
): AxiosPromise<BenefitPolicyVersionVO> =>
  post(`${MEMBER_BENEFIT_ENDPOINTS.policies}/${benefitUlid(policyId, 'policyId')}/versions/${benefitUlid(versionId, 'versionId')}/${action}`, {
    ...data,
    contentSha256: benefitSha256(data.contentSha256)
  });

export const createMemberPriceVersion = (data: {
  commandId: string;
  versionId: string;
  bookCode: string;
  versionNo: number;
  storeId?: number;
  items: Array<{ itemId: string; levelCode: string; skuId: number; unitId: number; amountMinor: number }>;
  correlationId: string;
}): AxiosPromise<MemberPriceVersionVO> =>
  post(MEMBER_BENEFIT_ENDPOINTS.prices, {
    ...data,
    items: data.items.map((item) => ({ ...item, amountMinor: benefitMinor(item.amountMinor) }))
  });

export const transitionMemberPrice = (
  versionId: string,
  action: 'validate' | 'approve' | 'publish',
  data: { commandId: string; contentSha256: string; effectiveAt?: string; expiresAt?: string; correlationId: string }
): AxiosPromise<MemberPriceVersionVO> =>
  post(`${MEMBER_BENEFIT_ENDPOINTS.prices}/${benefitUlid(versionId, 'versionId')}/${action}`, {
    ...data,
    contentSha256: benefitSha256(data.contentSha256)
  });

export const publishMemberBenefitPackage = (data: {
  storeId: string;
  packageVersion: number;
  previousVersion: number;
  expiresAt: string;
  correlationId: string;
}): AxiosPromise<MemberBenefitPackageVO> => post(MEMBER_BENEFIT_ENDPOINTS.packages, data);

export { MEMBER_BENEFIT_ENDPOINTS } from './contract';
