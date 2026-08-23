import { describe, expect, it } from 'vitest';
import { subscriptionTenant, subscriptionUlid, trustedSubscriptionPayload } from '../contract';

describe('Subscription 前端可信契约', () => {
  it('规范化标识并拒绝路径穿越', () => {
    expect(subscriptionUlid('01k00000000000000000000000')).toBe('01K00000000000000000000000');
    expect(subscriptionTenant('TENANT_A')).toBe('TENANT_A');
    expect(() => subscriptionTenant('../A')).toThrow('SUB-WEB-002');
  });
  it('拒绝客户端提交服务端权威订阅字段', () => {
    expect(trustedSubscriptionPayload({ contractRef: 'CONTRACT-1' })).toEqual({ contractRef: 'CONTRACT-1' });
    expect(() => trustedSubscriptionPayload({ state: 'ACTIVE' })).toThrow('SUB-WEB-003');
    expect(() => trustedSubscriptionPayload({ nested: { accessMode: 'NORMAL' } })).toThrow('SUB-WEB-003');
  });
});
