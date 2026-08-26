import { describe, expect, it } from 'vitest';
import { newUlid, parseStoreIds, REPORTING_ENDPOINTS, trustedReportingPayload } from '../contract';

describe('Gate 5D reporting client contract', () => {
  it('uses only versioned first-party reporting endpoints', () => {
    expect(Object.values(REPORTING_ENDPOINTS)).toHaveLength(6);
    expect(Object.values(REPORTING_ENDPOINTS).every((path) => /^\/api\/v[12]\//.test(path))).toBe(true);
    expect(REPORTING_ENDPOINTS.salesDaily).toBe('/api/v1/reports/sales-daily');
    expect(REPORTING_ENDPOINTS.salesDailyV2).toBe('/api/v2/reports/sales-daily');
    expect(REPORTING_ENDPOINTS.paymentReconciliationManage).toBe('/api/v1/reporting/payment-reconciliation');
  });

  it('rejects nested tenant override and normalizes store scope', () => {
    expect(() => trustedReportingPayload({ nested: [{ tenant_id: 'TENANT_B' }] })).toThrow('RPT-IAM-001');
    expect(trustedReportingPayload({ storeId: '11' })).toEqual({ storeId: '11' });
    expect(parseStoreIds('11, 12,11')).toEqual(['11', '12']);
    expect(() => parseStoreIds('0')).toThrow('RPT-G5D-072');
  });

  it('creates valid non-reused ULIDs without client tenant data', () => {
    const first = newUlid(1_786_934_400_000);
    const second = newUlid(1_786_934_400_000);
    expect(first).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
    expect(second).not.toBe(first);
  });
});
