import { describe, expect, it, vi } from 'vitest';
import { useStableOperationIdentity } from '../useStableOperationIdentity';

describe('G9A-R3C R0 稳定操作身份', () => {
  it('失败恢复持续复用原键，只有服务端确认成功后才生成下一键', () => {
    const factory = vi.fn().mockReturnValueOnce('idem-r3c-001').mockReturnValueOnce('idem-r3c-002');
    const identities = useStableOperationIdentity(factory);

    expect(identities.get('terminal:device-1:revoke')).toBe('idem-r3c-001');
    expect(identities.get('terminal:device-1:revoke')).toBe('idem-r3c-001');
    expect(identities.peek('terminal:device-1:revoke')).toBe('idem-r3c-001');
    expect(factory).toHaveBeenCalledTimes(1);

    identities.complete('terminal:device-1:revoke');
    expect(identities.get('terminal:device-1:revoke')).toBe('idem-r3c-002');
    expect(factory).toHaveBeenCalledTimes(2);
  });

  it('不同对象和动作绝不共享操作身份', () => {
    let sequence = 0;
    const identities = useStableOperationIdentity(() => `idem-${++sequence}`);

    expect(identities.get('report:export-1:approve')).toBe('idem-1');
    expect(identities.get('report:export-1:generate')).toBe('idem-2');
    expect(identities.get('report:export-2:approve')).toBe('idem-3');
  });
});
