const TENANT_KEYS = new Set(['tenantid', 'tenant_id', 'tenant']);
const ULID_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';
const ULID_PATTERN = /^[0-9A-HJKMNP-TV-Z]{26}$/;
const PLATFORM_ID_PATTERN = /^[1-9][0-9]{0,18}$/;

/** Gate 6E 后台第二波只允许访问既有 Owner 的版本化正式端点。 */
export const OPERATIONS_ENDPOINTS = Object.freeze({
  inventory: '/api/v1/inventory',
  stocktakes: '/api/v1/inventory/stocktakes',
  costing: '/api/inventory',
  procurement: '/api/v1/procurement',
  transfers: '/api/v1/inventory/transfers',
  promotions: '/api/v1/promotions',
  members: '/api/v1/members',
  privacyRequests: '/api/v1/privacy-requests',
  releases: '/api/v1/releases'
});

/**
 * 阻断客户端租户覆写和原型污染键。最终租户、组织、门店和仓库授权仍由服务端可信上下文执行。
 */
export function trustedOperationsPayload<T>(value: T): T {
  const inspect = (item: unknown): void => {
    if (Array.isArray(item)) return item.forEach(inspect);
    if (item && typeof item === 'object') {
      Object.entries(item as Record<string, unknown>).forEach(([key, nested]) => {
        const normalized = key.replace(/[-\s]/g, '').toLowerCase();
        if (TENANT_KEYS.has(normalized) || ['__proto__', 'prototype', 'constructor'].includes(key)) {
          throw new Error('ADM-IAM-002: 客户端不得提交租户标识或危险对象键');
        }
        inspect(nested);
      });
    }
  };
  inspect(value);
  return value;
}

/** 生成标准 26 位 ULID，用作一次受控后台命令的稳定幂等键或关联标识。 */
export function newOperationCommandId(now = Date.now()): string {
  let timestamp = now;
  let prefix = '';
  for (let index = 0; index < 10; index += 1) {
    prefix = ULID_ALPHABET[timestamp % 32] + prefix;
    timestamp = Math.floor(timestamp / 32);
  }
  const random = new Uint8Array(16);
  crypto.getRandomValues(random);
  return prefix + Array.from(random, (value) => ULID_ALPHABET[value & 31]).join('');
}

/** 在进入 URL path 前校验 Owner 的 ULID，避免路径拼接注入。 */
export function ownerUlid(value: string, field = 'id'): string {
  const normalized = value.trim().toUpperCase();
  if (!ULID_PATTERN.test(normalized)) throw new Error(`ADM-INPUT-001: ${field} 必须为标准 ULID`);
  return normalized;
}

/** 平台 BIGINT 以字符串传输，避免 JavaScript 大整数精度损失。 */
export function platformId(value: string, field = 'id'): string {
  const normalized = value.trim();
  if (!PLATFORM_ID_PATTERN.test(normalized)) throw new Error(`ADM-INPUT-002: ${field} 必须为正整数标识`);
  return normalized;
}
