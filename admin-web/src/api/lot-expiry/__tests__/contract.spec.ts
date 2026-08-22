import { describe, expect, it } from 'vitest';
import { lotUlid, trustedLotPayload } from '../contract';

describe('LOT-001 web trust boundary', () => {
  it('normalizes ULID but rejects malformed identity', () => {
    expect(lotUlid('01k2a000000000000000000071')).toBe('01K2A000000000000000000071');
    expect(() => lotUlid('lot-1')).toThrow(/LOT-WEB-001/);
  });

  it('rejects tenant, inventory, expiry and cost owner facts', () => {
    for (const key of ['tenantId', 'industry', 'expiryStatus', 'onHandQuantity', 'unitCost']) {
      expect(() => trustedLotPayload({ [key]: 'forged' })).toThrow(/LOT-WEB-002/);
    }
  });

  it('allows only policy command inputs', () => {
    expect(
      trustedLotPayload({
        policyVersionId: '01K2A000000000000000000061',
        storeId: 1101,
        skuId: 701,
        enabled: true,
        expiryBasis: 'EXPLICIT_EXPIRY_DATE',
        nearExpiryDays: 3,
        effectiveFrom: '2026-08-23T00:00:00Z'
      })
    ).toMatchObject({ skuId: 701, enabled: true });
  });
});
