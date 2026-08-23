import { describe, expect, it } from 'vitest';
import { serviceIdentityValue, serviceUlid, trustedServicePayload } from '../contract';

describe('T2-SVC-001 Web 契约边界', () => {
  it('标准化并校验 ULID 与幂等标识', () => {
    expect(serviceUlid('01k00000000000000000000000')).toBe('01K00000000000000000000000');
    expect(serviceIdentityValue('command-001')).toBe('command-001');
    expect(() => serviceUlid('../ticket')).toThrow('SVC-WEB-001');
    expect(() => serviceIdentityValue('short')).toThrow('SVC-WEB-002');
  });

  it('拒绝页面提交服务端权威状态和对象存储字段', () => {
    expect(trustedServicePayload({ subject: '内部工单', reason: '受控处理' })).toEqual({ subject: '内部工单', reason: '受控处理' });
    expect(() => trustedServicePayload({ nested: { tenantId: 'TENANT_A' } })).toThrow('SVC-WEB-003');
    expect(() => trustedServicePayload({ objectKey: 'service/private' })).toThrow('SVC-WEB-003');
    expect(() => trustedServicePayload({ state: 'CLOSED' })).toThrow('SVC-WEB-003');
  });
});
