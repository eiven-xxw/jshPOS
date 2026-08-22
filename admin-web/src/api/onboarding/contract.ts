const ULID = /^[0-9A-HJKMNP-TV-Z]{26}$/;
const FORBIDDEN = new Set(['tenantId', 'state', 'checks', 'checkRun', 'snapshotSha256', 'ownerType', 'factSha256']);

export const ONBOARDING_ENDPOINT = '/api/v1/onboarding/plans';

export const onboardingUlid = (value: string): string => {
  const normalized = value.trim().toUpperCase();
  if (!ULID.test(normalized)) throw new Error('ONB-WEB-001: 计划标识必须为 ULID');
  return normalized;
};

/** 防止页面把租户、状态或检查事实混入正式请求。 */
export const trustedOnboardingPayload = <T extends Record<string, unknown>>(value: T): T => {
  const inspect = (current: unknown): void => {
    if (Array.isArray(current)) return current.forEach(inspect);
    if (current && typeof current === 'object') {
      for (const [key, item] of Object.entries(current as Record<string, unknown>)) {
        if (FORBIDDEN.has(key)) throw new Error(`ONB-WEB-002: 禁止提交服务端权威字段 ${key}`);
        inspect(item);
      }
    }
  };
  inspect(value);
  return value;
};
