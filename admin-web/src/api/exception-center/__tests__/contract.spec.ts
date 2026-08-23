import { describe, expect, it } from 'vitest';
import { EXCEPTION_CENTER_ENDPOINT, exceptionCaseId, trustedExceptionPayload } from '../contract';

describe('T2-EXC-001 exception API boundary', () => {
  it('uses the frozen v1 endpoint and ULID identity', () => {
    expect(EXCEPTION_CENTER_ENDPOINT).toBe('/api/v1/operations/exceptions');
    expect(exceptionCaseId('01K3M000000000000000000001')).toHaveLength(26);
    expect(() => exceptionCaseId('../tenant-b')).toThrow('标识无效');
  });
  it('rejects client-authored owner facts', () => {
    expect(trustedExceptionPayload({ storeId: 1, reason: '受审计的Owner处置计划' })).toEqual({ storeId: 1, reason: '受审计的Owner处置计划' });
    for (const field of ['tenantId', 'sourceSha256', 'severity', 'ownerResult', 'state']) {
      expect(() => trustedExceptionPayload({ [field]: 'forged' })).toThrow('权威字段');
    }
  });
});
