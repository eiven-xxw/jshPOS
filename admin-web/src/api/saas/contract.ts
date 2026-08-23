const ULID = /^[0-9A-HJKMNP-TV-Z]{26}$/;
const TENANT = /^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$/;
const FORBIDDEN = new Set(['tenantId', 'state', 'approverUserId', 'technicalTenantId', 'lifecycleState', 'quotaUsed']);

export const SAAS_ENDPOINT = '/api/v1/saas';
export const saasUlid = (value: string) => {
  const normalized = value.trim().toUpperCase();
  if (!ULID.test(normalized)) throw new Error('SAA-WEB-001: 标识必须为 ULID');
  return normalized;
};
export const saasTenant = (value: string) => {
  const normalized = value.trim();
  if (!TENANT.test(normalized)) throw new Error('SAA-WEB-002: 租户标识格式非法');
  return normalized;
};
/** 禁止页面把服务端权威租户、状态、审批或配额事实混入请求体。 */
export const trustedSaasPayload = <T extends Record<string, unknown>>(value: T): T => {
  const inspect = (current: unknown): void => {
    if (Array.isArray(current)) return current.forEach(inspect);
    if (current && typeof current === 'object')
      for (const [key, item] of Object.entries(current as Record<string, unknown>)) {
        if (FORBIDDEN.has(key)) throw new Error(`SAA-WEB-003: 禁止提交服务端权威字段 ${key}`);
        inspect(item);
      }
  };
  inspect(value);
  return value;
};
