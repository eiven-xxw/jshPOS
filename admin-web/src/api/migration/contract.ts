const TENANT_KEYS = new Set(['tenant', 'tenantid', 'tenant_id']);
const ULID_PATTERN = /^[0-9A-HJKMNP-TV-Z]{26}$/;

/** T2-DMT-001 只允许访问服务端可恢复迁移编排端点。 */
export const MIGRATION_ENDPOINT = '/api/v1/business-migrations';

/** 阻断前端伪造租户和原型污染字段；最终租户必须来自服务端可信上下文。 */
export function trustedMigrationPayload<T>(value: T): T {
  const inspect = (item: unknown): void => {
    if (Array.isArray(item)) return item.forEach(inspect);
    if (item && typeof item === 'object' && !(item instanceof Blob) && !(item instanceof FormData)) {
      Object.entries(item as Record<string, unknown>).forEach(([key, nested]) => {
        const normalized = key.replace(/[-\s]/g, '').toLowerCase();
        if (TENANT_KEYS.has(normalized) || ['__proto__', 'prototype', 'constructor'].includes(key)) {
          throw new Error('DMT-IAM-001: 客户端不得提交租户标识或危险对象键');
        }
        inspect(nested);
      });
    }
  };
  inspect(value);
  return value;
}

/** 校验迁移批次的稳定 ULID，避免路径拼接注入。 */
export function migrationUlid(value: string): string {
  const normalized = value.trim().toUpperCase();
  if (!ULID_PATTERN.test(normalized)) throw new Error('DMT-INPUT-001: batchId 必须为标准 ULID');
  return normalized;
}

/** 浏览器离线计算原文件 SHA-256，不上传内容到任何其他端点。 */
export async function sha256Hex(file: File): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return Array.from(new Uint8Array(digest), (value) => value.toString(16).padStart(2, '0')).join('');
}
