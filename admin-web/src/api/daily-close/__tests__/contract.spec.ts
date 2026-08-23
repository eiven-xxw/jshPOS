import { describe, expect, it } from 'vitest';
import { dailyCloseUlid, trustedDailyClosePayload } from '../contract';

describe('T2-CLS-001 web contract', () => {
  it('normalizes ULID and accepts only client-owned fields', () => {
    expect(dailyCloseUlid('01k3m000000000000000000001')).toBe('01K3M000000000000000000001');
    expect(trustedDailyClosePayload({ storeId: 1, businessDate: '2026-08-23' })).toEqual({ storeId: 1, businessDate: '2026-08-23' });
  });

  it('rejects malformed IDs and server-owned facts recursively', () => {
    expect(() => dailyCloseUlid('bad')).toThrow('CLS-WEB-001');
    expect(() => trustedDailyClosePayload({ tenantId: 'TENANT_A' })).toThrow('CLS-WEB-002');
    expect(() => trustedDailyClosePayload({ nested: { state: 'CLOSED' } })).toThrow('CLS-WEB-002');
    expect(() => trustedDailyClosePayload({ rows: [{ receivableMinor: 100 }] })).toThrow('CLS-WEB-002');
  });
});
