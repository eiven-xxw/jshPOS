import { describe, expect, it } from 'vitest';
import { assertMinorAmount, assertPositiveExactInteger, CATALOG_ENDPOINTS, trustedCatalogPayload } from '../contract';

describe('Gate 1 catalog contract', () => {
  it('uses only versioned first-party catalog endpoints', () => {
    expect(Object.values(CATALOG_ENDPOINTS)).toHaveLength(5);
    expect(Object.values(CATALOG_ENDPOINTS).every((path) => path.startsWith('/api/v1/catalog'))).toBe(true);
  });

  it('rejects nested tenant override attempts', () => {
    expect(() => trustedCatalogPayload({ skuCode: 'A', tenantId: 'TENANT_B' })).toThrow('FND-IAM-004');
    expect(() => trustedCatalogPayload({ rows: [{ tenant_id: 'TENANT_B' }] })).toThrow('FND-IAM-004');
    expect(() => trustedCatalogPayload({ skuCode: 'A', attributes: { color: 'red' } })).not.toThrow();
  });

  it('accepts only integer minor amounts', () => {
    expect(assertMinorAmount(0)).toBe(0);
    expect(assertMinorAmount(1999)).toBe(1999);
    expect(() => assertMinorAmount(-1)).toThrow('CAT-PRC-001');
    expect(() => assertMinorAmount(1.5)).toThrow('CAT-PRC-001');
    expect(() => assertMinorAmount(Number.MAX_SAFE_INTEGER + 1)).toThrow('CAT-PRC-001');
  });

  it('accepts only exact positive unit ratios', () => {
    expect(assertPositiveExactInteger(12, 'n')).toBe(12);
    expect(() => assertPositiveExactInteger(0, 'n')).toThrow('CAT-PRD-008');
    expect(() => assertPositiveExactInteger(1.1, 'n')).toThrow('CAT-PRD-008');
  });

  it('preserves 64-bit identifiers as strings', () => {
    const id = '1844674407370955161';
    const payload = trustedCatalogPayload({ skuId: id });
    expect(payload.skuId).toBe(id);
  });
});
