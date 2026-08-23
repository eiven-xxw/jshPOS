const ULID = /^[0-9A-HJKMNP-TV-Z]{26}$/;
const FORBIDDEN = new Set([
  'tenantId',
  'state',
  'snapshotSha256',
  'manifestSha256',
  'grossMinor',
  'discountMinor',
  'surchargeMinor',
  'receivableMinor',
  'refundMinor',
  'cashReceivedMinor',
  'cashRefundedMinor',
  'electronicReceivedMinor',
  'electronicRefundedMinor',
  'unknownPaymentCount',
  'unknownRefundCount',
  'difference'
]);

export const DAILY_CLOSE_ENDPOINT = '/api/v1/operations/daily-closes';

export const dailyCloseUlid = (value: string): string => {
  const normalized = value.trim().toUpperCase();
  if (!ULID.test(normalized)) throw new Error('CLS-WEB-001: 日结标识必须为 ULID');
  return normalized;
};

/** 页面不得提交服务端权威租户、金额、差异、摘要或目标状态。 */
export const trustedDailyClosePayload = <T extends Record<string, unknown>>(value: T): T => {
  const inspect = (current: unknown): void => {
    if (Array.isArray(current)) return current.forEach(inspect);
    if (current && typeof current === 'object') {
      for (const [key, item] of Object.entries(current as Record<string, unknown>)) {
        if (FORBIDDEN.has(key)) throw new Error(`CLS-WEB-002: 禁止提交服务端权威字段 ${key}`);
        inspect(item);
      }
    }
  };
  inspect(value);
  return value;
};
