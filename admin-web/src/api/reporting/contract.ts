const TENANT_KEYS = new Set(['tenantid', 'tenant_id', 'tenant']);
const ULID_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

export const REPORTING_ENDPOINTS = Object.freeze({
  salesDaily: '/api/v1/reports/sales-daily',
  inventoryCostDaily: '/api/v1/reports/inventory-cost-daily',
  paymentReconciliation: '/api/v1/reports/payment-reconciliation',
  paymentReconciliationManage: '/api/v1/reporting/payment-reconciliation',
  exports: '/api/v1/report-exports'
});

/** 前端只阻断明显误用，权威租户与门店范围仍由服务端校验。 */
export function trustedReportingPayload<T>(value: T): T {
  const inspect = (item: unknown): void => {
    if (Array.isArray(item)) return item.forEach(inspect);
    if (item && typeof item === 'object') {
      Object.entries(item as Record<string, unknown>).forEach(([key, nested]) => {
        if (TENANT_KEYS.has(key.replace(/[-\s]/g, '').toLowerCase())) {
          throw new Error('RPT-IAM-001: 客户端不得提交租户标识');
        }
        inspect(nested);
      });
    }
  };
  inspect(value);
  return value;
}

/** 生成时间有序的标准 26 位 ULID，仅作客户端命令幂等标识。 */
export function newUlid(now = Date.now()): string {
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

export function parseStoreIds(value: string): Id64[] {
  const stores = [
    ...new Set(
      value
        .split(',')
        .map((item) => item.trim())
        .filter(Boolean)
    )
  ];
  if (!stores.length || stores.length > 50 || stores.some((item) => !/^[1-9]\d{0,18}$/.test(item))) {
    throw new Error('RPT-G5D-072: 门店 ID 必须为 1 至 50 个正整数');
  }
  return stores;
}

type Id64 = string | number;
