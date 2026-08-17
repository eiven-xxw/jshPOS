import { describe, expect, it } from 'vitest';
import { newTerminalCommandKey, TERMINAL_ENDPOINTS, trustedTerminalPayload } from '../contract';

describe('Gate 6A terminal client contract', () => {
  it('uses only versioned first-party terminal endpoints', () => {
    expect(Object.values(TERMINAL_ENDPOINTS)).toHaveLength(2);
    expect(Object.values(TERMINAL_ENDPOINTS).every((path) => path.startsWith('/api/v1/'))).toBe(true);
  });

  it('rejects nested tenant override and keeps authorized terminal input', () => {
    expect(() => trustedTerminalPayload({ nested: [{ tenant_id: 'TENANT_B' }] })).toThrow('TRM-IAM-001');
    expect(trustedTerminalPayload({ storeId: 1101, profile: 'ANDROID_POS_V1' })).toEqual({
      storeId: 1101,
      profile: 'ANDROID_POS_V1'
    });
  });

  it('creates non-reused ULID command keys for credential rotation', () => {
    const first = newTerminalCommandKey(1_786_934_400_000);
    const second = newTerminalCommandKey(1_786_934_400_000);
    expect(first).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
    expect(second).not.toBe(first);
  });
});
