import { describe, expect, it } from 'vitest';
import { onboardingUlid, trustedOnboardingPayload } from '../contract';

describe('T2-ONB-001 web contract', () => {
  it('normalizes ULID and accepts only client-owned create fields', () => {
    expect(onboardingUlid('01k3m000000000000000000001')).toBe('01K3M000000000000000000001');
    expect(trustedOnboardingPayload({ sourceStoreId: 1, targetStoreId: 2, templateId: 3, templateVersionId: 4 })).toEqual({
      sourceStoreId: 1,
      targetStoreId: 2,
      templateId: 3,
      templateVersionId: 4
    });
  });

  it('rejects malformed IDs and server-owned facts recursively', () => {
    expect(() => onboardingUlid('bad')).toThrow('ONB-WEB-001');
    expect(() => trustedOnboardingPayload({ tenantId: 'TENANT_A' })).toThrow('ONB-WEB-002');
    expect(() => trustedOnboardingPayload({ nested: { state: 'OPENED' } })).toThrow('ONB-WEB-002');
    expect(() => trustedOnboardingPayload({ rows: [{ factSha256: 'a'.repeat(64) }] })).toThrow('ONB-WEB-002');
  });
});
