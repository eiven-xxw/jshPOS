import { assertNoClientTenantOverride } from '@/api/foundation/contract';

export const CATALOG_ENDPOINTS = Object.freeze({
  root: '/api/v1/catalog',
  products: '/api/v1/catalog/products',
  imports: '/api/v1/catalog/imports',
  priceBooks: '/api/v1/catalog/price-books',
  packages: '/api/v1/catalog/packages',
  shelfLabels: '/api/v1/catalog/shelf-labels'
});

/** 构造不含租户值的稳定写命令标识。 */
export function shelfLabelCommandIdentity(prefix: string): { idempotencyKey: string; correlationId: string } {
  const random = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return { idempotencyKey: `${prefix}:${random}`, correlationId: `lbl:${random}` };
}

export function trustedCatalogPayload<T>(value: T): T {
  assertNoClientTenantOverride(value);
  return value;
}

export function assertMinorAmount(value: number): number {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new Error('CAT-PRC-001: 金额必须是非负最小货币单位安全整数');
  }
  return value;
}

export function assertPositiveExactInteger(value: number, field: string): number {
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`CAT-PRD-008: ${field} 必须是正安全整数`);
  }
  return value;
}
