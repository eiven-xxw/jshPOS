import { describe, expect, it } from 'vitest';
import { saasTenant, saasUlid, trustedSaasPayload } from '../contract';

describe('SaaS 前端可信契约', () => {
  it('规范化 ULID 和校验租户路径标识', () => {
    expect(saasUlid('01k00000000000000000000000')).toBe('01K00000000000000000000000');
    expect(saasTenant('TENANT_A')).toBe('TENANT_A');
    expect(() => saasUlid('bad')).toThrow('SAA-WEB-001');
    expect(() => saasTenant('../TENANT')).toThrow('SAA-WEB-002');
  });
  it('拒绝客户端权威字段并允许普通业务输入', () => {
    expect(trustedSaasPayload({ companyName: '虚构商户', planId: 1 })).toEqual({ companyName: '虚构商户', planId: 1 });
    expect(() => trustedSaasPayload({ tenantId: 'TENANT_A' })).toThrow('SAA-WEB-003');
    expect(() => trustedSaasPayload({ nested: [{ state: 'ACTIVE' }] })).toThrow('SAA-WEB-003');
  });
});
