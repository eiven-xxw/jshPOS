import { describe, expect, it } from 'vitest';
import { newOperationCommandId, OPERATIONS_ENDPOINTS, ownerUlid, platformId, trustedOperationsPayload } from '../contract';

describe('Gate 6E operations client contract', () => {
  it('pins every supported Owner to a first-party API path', () => {
    expect(Object.keys(OPERATIONS_ENDPOINTS)).toEqual([
      'inventory',
      'stocktakes',
      'costing',
      'procurement',
      'replenishment',
      'transfers',
      'promotions',
      'members',
      'privacyRequests',
      'releases'
    ]);
    expect(Object.values(OPERATIONS_ENDPOINTS).every((path) => path.startsWith('/api/'))).toBe(true);
  });

  it('rejects nested tenant overrides and prototype pollution keys', () => {
    expect(() => trustedOperationsPayload({ rows: [{ tenant_id: 'TENANT-B' }] })).toThrow('ADM-IAM-002');
    expect(() => trustedOperationsPayload(JSON.parse('{"constructor":{"prototype":{"polluted":true}}}'))).toThrow('ADM-IAM-002');
    expect(trustedOperationsPayload({ storeId: '1101', reason: '批准受控操作' })).toEqual({ storeId: '1101', reason: '批准受控操作' });
  });

  it('validates identifiers before they enter a URL path', () => {
    expect(ownerUlid('01j00000000000000000000001')).toBe('01J00000000000000000000001');
    expect(platformId('1844674407370955161')).toBe('1844674407370955161');
    expect(() => ownerUlid('../escape')).toThrow('ADM-INPUT-001');
    expect(() => platformId('1/../../admin')).toThrow('ADM-INPUT-002');
  });

  it('creates stable-shape but non-reused command identifiers', () => {
    const first = newOperationCommandId(1_786_934_400_000);
    const second = newOperationCommandId(1_786_934_400_000);
    expect(first).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
    expect(second).not.toBe(first);
  });
});
