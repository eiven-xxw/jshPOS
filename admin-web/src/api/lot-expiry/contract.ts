export const LOT_POLICY_ENDPOINT = '/api/v1/catalog/lot-policies';
export const LOT_INVENTORY_ENDPOINT = '/api/v1/inventory/lots';

const ULID = /^[0-9A-HJKMNP-TV-Z]{26}$/;
const FORBIDDEN = new Set([
  'tenantId',
  'industry',
  'state',
  'expiryStatus',
  'onHandQuantity',
  'lastLedgerSequence',
  'sourceSha256',
  'cost',
  'unitCost'
]);

export const lotUlid = (value: string): string => {
  const normalized = value.trim().toUpperCase();
  if (!ULID.test(normalized)) throw new Error('LOT-WEB-001: 标识必须为规范 ULID');
  return normalized;
};

/** 页面不得提交租户、行业、库存余额、效期状态或成本等 Owner 权威字段。 */
export const trustedLotPayload = <T extends Record<string, unknown>>(value: T): T => {
  const inspect = (current: unknown): void => {
    if (Array.isArray(current)) return current.forEach(inspect);
    if (current && typeof current === 'object') {
      for (const [key, item] of Object.entries(current as Record<string, unknown>)) {
        if (FORBIDDEN.has(key)) throw new Error(`LOT-WEB-002: 禁止提交服务端权威字段 ${key}`);
        inspect(item);
      }
    }
  };
  inspect(value);
  return value;
};
