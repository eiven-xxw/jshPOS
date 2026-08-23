const ULID = /^[0-9A-HJKMNP-TV-Z]{26}$/;
const TENANT = /^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$/;
const FORBIDDEN = new Set(['tenantId', 'targetTenantId', 'state', 'stateVersion', 'accessMode', 'planId', 'entitlementVersionId']);

export const SUBSCRIPTION_ENDPOINT = '/api/v1/subscriptions';
export const subscriptionUlid = (value: string) => {
  const normalized = value.trim().toUpperCase();
  if (!ULID.test(normalized)) throw new Error('SUB-WEB-001: 订阅标识必须为 ULID');
  return normalized;
};
export const subscriptionTenant = (value: string) => {
  const normalized = value.trim();
  if (!TENANT.test(normalized)) throw new Error('SUB-WEB-002: 目标租户格式非法');
  return normalized;
};
/** 页面不得提交服务端权威租户、状态、访问模式或套餐快照。 */
export const trustedSubscriptionPayload = <T extends object>(value: T): T => {
  const inspect = (current: unknown): void => {
    if (Array.isArray(current)) return current.forEach(inspect);
    if (current && typeof current === 'object')
      for (const [key, item] of Object.entries(current as Record<string, unknown>)) {
        if (FORBIDDEN.has(key)) throw new Error(`SUB-WEB-003: 禁止提交服务端权威字段 ${key}`);
        inspect(item);
      }
  };
  inspect(value);
  return value;
};
