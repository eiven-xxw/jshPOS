const ULID = /^[0-9A-HJKMNP-TV-Z]{26}$/;
const SAFE = /^[A-Za-z0-9._:-]+$/;
const FORBIDDEN = new Set(['tenantId', 'state', 'recordVersion', 'objectKey', 'downloadUrl', 'closedBy', 'resolvedBy']);

export const SERVICE_ENDPOINT = '/api/v1/service';

export const serviceUlid = (value: string): string => {
  const normalized = value.trim().toUpperCase();
  if (!ULID.test(normalized)) throw new Error('SVC-WEB-001: 服务事实标识必须为 ULID');
  return normalized;
};

export const serviceIdentityValue = (value: string): string => {
  const normalized = value.trim();
  if (normalized.length < 8 || normalized.length > 64 || !SAFE.test(normalized)) {
    throw new Error('SVC-WEB-002: 幂等键或关联标识格式非法');
  }
  return normalized;
};

/** 页面不得提交可信租户、服务端状态、对象键、职责分离结论或下载地址。 */
export const trustedServicePayload = <T extends object>(value: T): T => {
  const inspect = (current: unknown): void => {
    if (Array.isArray(current)) return current.forEach(inspect);
    if (current && typeof current === 'object')
      for (const [key, item] of Object.entries(current as Record<string, unknown>)) {
        if (FORBIDDEN.has(key)) throw new Error(`SVC-WEB-003: 禁止提交服务端权威字段 ${key}`);
        inspect(item);
      }
  };
  inspect(value);
  return value;
};
