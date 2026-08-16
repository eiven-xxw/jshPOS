import { describe, expect, it } from 'vitest';
import { assertNoClientTenantOverride, FOUNDATION_ENDPOINTS, validateStaffScopeShape } from '../contract';

describe('Gate 0 foundation contract', () => {
  it('uses only versioned first-party foundation endpoints', () => {
    expect(Object.values(FOUNDATION_ENDPOINTS)).toHaveLength(5);
    expect(Object.values(FOUNDATION_ENDPOINTS).every((path) => path.startsWith('/api/v1/foundation/'))).toBe(true);
  });

  it('fails early for nested tenant override attempts', () => {
    expect(() => assertNoClientTenantOverride({ code: 'A101', tenantId: 'TENANT_B' })).toThrow('FND-IAM-004');
    expect(() => assertNoClientTenantOverride({ nested: [{ tenant_id: 'TENANT_B' }] })).toThrow('FND-IAM-004');
    expect(() => assertNoClientTenantOverride({ code: 'A101', name: '虚构门店' })).not.toThrow();
  });

  it('validates all three data-scope shapes', () => {
    expect(validateStaffScopeShape({ scopeType: 'TENANT' })).toBe(true);
    expect(validateStaffScopeShape({ scopeType: 'ORG_SUBTREE', orgUnitId: 100 })).toBe(true);
    expect(validateStaffScopeShape({ scopeType: 'STORE', storeId: 101 })).toBe(true);
    expect(validateStaffScopeShape({ scopeType: 'STORE', orgUnitId: 100, storeId: 101 })).toBe(false);
  });
});
