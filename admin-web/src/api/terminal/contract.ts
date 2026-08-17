const TENANT_KEYS = new Set(['tenantid', 'tenant_id', 'tenant']);
const ULID_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';

/** Gate 6A 终端后台只调用版本化的一方接口。 */
export const TERMINAL_ENDPOINTS = Object.freeze({
  terminals: '/api/v1/terminals',
  activations: '/api/v1/terminal-activations'
});

/** 前端阻断租户覆写误用，最终授权仍由服务端可信上下文执行。 */
export function trustedTerminalPayload<T>(value: T): T {
  const inspect = (item: unknown): void => {
    if (Array.isArray(item)) return item.forEach(inspect);
    if (item && typeof item === 'object') {
      Object.entries(item as Record<string, unknown>).forEach(([key, nested]) => {
        if (TENANT_KEYS.has(key.replace(/[-\s]/g, '').toLowerCase())) {
          throw new Error('TRM-IAM-001: 客户端不得提交租户标识');
        }
        inspect(nested);
      });
    }
  };
  inspect(value);
  return value;
}

/** 生成仅用于后台终端命令幂等性的标准 ULID。 */
export function newTerminalCommandKey(now = Date.now()): string {
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
