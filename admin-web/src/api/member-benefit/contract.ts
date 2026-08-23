const TENANT_KEYS = new Set(['tenant', 'tenantid', 'tenant_id']);
const ULID = /^[0-9A-HJKMNP-TV-Z]{26}$/;
const SHA256 = /^[a-f0-9]{64}$/;

export const MEMBER_BENEFIT_ENDPOINTS = Object.freeze({
  policies: '/api/v1/member-benefit-policies',
  prices: '/api/v1/member-price-versions',
  packages: '/api/v1/promotions/member-benefit-packages'
});

/** 权益运营载荷禁止携带租户和危险原型键，租户只能来自可信登录上下文。 */
export function trustedMemberBenefitPayload<T>(value: T): T {
  const inspect = (item: unknown): void => {
    if (Array.isArray(item)) return item.forEach(inspect);
    if (item && typeof item === 'object') {
      Object.entries(item as Record<string, unknown>).forEach(([key, nested]) => {
        const normalized = key.replace(/[-\s]/g, '').toLowerCase();
        if (TENANT_KEYS.has(normalized) || ['__proto__', 'prototype', 'constructor'].includes(key)) {
          throw new Error('MEM003-IAM-001: 客户端不得提交租户标识或危险对象键');
        }
        inspect(nested);
      });
    }
  };
  inspect(value);
  return value;
}

export function benefitUlid(value: string, field: string): string {
  const normalized = value.trim().toUpperCase();
  if (!ULID.test(normalized)) throw new Error(`MEM003-INPUT-001: ${field} 必须为标准 ULID`);
  return normalized;
}

export function benefitSha256(value: string): string {
  if (!SHA256.test(value)) throw new Error('MEM003-INPUT-002: 内容摘要无效');
  return value;
}

/** 金额必须是 JavaScript 安全范围内的非负整数分值，页面不得使用浮点计算。 */
export function benefitMinor(value: number): number {
  if (!Number.isSafeInteger(value) || value < 0) throw new Error('MEM003-MONEY-001: 会员价必须为安全非负整数分值');
  return value;
}
