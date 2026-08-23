import { describe, expect, it } from 'vitest';
import { benefitMinor, benefitSha256, benefitUlid, MEMBER_BENEFIT_ENDPOINTS, trustedMemberBenefitPayload } from '../contract';

describe('T2-MEM-003 member benefit client contract', () => {
  it('pins every Owner endpoint to a first-party versioned API', () => {
    expect(MEMBER_BENEFIT_ENDPOINTS).toEqual({
      policies: '/api/v1/member-benefit-policies',
      prices: '/api/v1/member-price-versions',
      packages: '/api/v1/promotions/member-benefit-packages'
    });
  });

  it('fails closed on tenant override, unsafe identifiers and floating money', () => {
    expect(() => trustedMemberBenefitPayload({ rows: [{ tenant_id: 'TENANT_B' }] })).toThrow('MEM003-IAM-001');
    expect(() => benefitUlid('../escape', 'versionId')).toThrow('MEM003-INPUT-001');
    expect(() => benefitSha256('not-a-digest')).toThrow('MEM003-INPUT-002');
    expect(() => benefitMinor(12.5)).toThrow('MEM003-MONEY-001');
    expect(benefitMinor(1200)).toBe(1200);
  });
});
