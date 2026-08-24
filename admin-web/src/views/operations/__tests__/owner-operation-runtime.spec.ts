// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest';

const elementPlus = vi.hoisted(() => ({
  confirm: vi.fn(),
  success: vi.fn(),
  error: vi.fn(),
  warning: vi.fn()
}));
const operationApi = vi.hoisted(() => ({
  next: 0,
  newOperationCommandId: vi.fn(() => `01J0000000000000000000000${++operationApi.next}`.slice(-26))
}));

vi.mock('element-plus', () => ({
  ElMessageBox: { confirm: elementPlus.confirm },
  ElMessage: { success: elementPlus.success, error: elementPlus.error, warning: elementPlus.warning }
}));
vi.mock('@/api/operations', () => ({ newOperationCommandId: operationApi.newOperationCommandId }));

import { useControlledOperation } from '../useControlledOperation';

const operation = (execute: () => Promise<{ data: { correlationId: string } }>) => ({
  owner: 'Inventory',
  objectId: '01J00000000000000000000011',
  currentState: 'PROJECTED',
  currentVersion: 7,
  action: 'REBUILD_BALANCE',
  impact: '只重建可丢弃投影，不修改不可变流水',
  reason: '投影摘要核对后执行重建',
  execute
});

describe('G9A-R3B R0 受控页面操作底座', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    operationApi.next = 0;
    elementPlus.confirm.mockResolvedValue(undefined);
  });

  it('同一对象动作的并发点击共享一次确认与一次服务端调用', async () => {
    let release!: (value: { data: { correlationId: string } }) => void;
    const pending = new Promise<{ data: { correlationId: string } }>((resolve) => {
      release = resolve;
    });
    const execute = vi.fn(() => pending);
    const page = useControlledOperation();

    const first = page.runControlled(operation(execute));
    const second = page.runControlled(operation(execute));
    await Promise.resolve();

    expect(elementPlus.confirm).toHaveBeenCalledTimes(1);
    expect(execute).toHaveBeenCalledTimes(1);
    release({ data: { correlationId: 'corr-r3b-r0-01' } });
    await Promise.all([first, second]);
    expect(page.pageState.value).toBe('SUCCEEDED');
  });

  it('网络或 5xx 未知结果保留原幂等键并禁止再次执行写命令', async () => {
    const execute = vi.fn().mockRejectedValue(
      Object.assign(new Error('unsafe-provider-body'), {
        isAxiosError: true,
        response: {
          status: 503,
          data: { code: 'OWNER_TIMEOUT', msg: 'Owner 结果未知，请查询原命令' },
          headers: { 'x-correlation-id': 'corr-r3b-r0-unknown' }
        }
      })
    );
    const page = useControlledOperation();

    await page.runControlled(operation(execute));
    const originalIdentity = page.pageFailure.value?.operationIdentity;
    await page.runControlled(operation(execute));

    expect(page.pageState.value).toBe('UNKNOWN');
    expect(page.pageFailure.value).toMatchObject({
      code: 'OWNER_TIMEOUT',
      correlationId: 'corr-r3b-r0-unknown',
      operationIdentity: originalIdentity
    });
    expect(execute).toHaveBeenCalledTimes(1);
  });

  it('读取空集合与失败分别进入 EMPTY 和 FAILED，且只展示安全错误信封', async () => {
    const page = useControlledOperation();
    const empty = await page.runRead(() => Promise.resolve({ data: [] as string[] }), (value) => value.length === 0);
    expect(empty).toEqual([]);
    expect(page.pageState.value).toBe('EMPTY');

    const failed = await page.runRead(() =>
      Promise.reject({
        response: {
          status: 403,
          data: { code: 'OWNER_FORBIDDEN', msg: '无权访问该门店' },
          headers: { 'x-correlation-id': 'corr-r3b-r0-read' }
        }
      })
    );
    expect(failed).toBeUndefined();
    expect(page.pageState.value).toBe('FAILED');
    expect(page.pageFailure.value).toEqual({
      code: 'OWNER_FORBIDDEN',
      message: '无权访问该门店',
      correlationId: 'corr-r3b-r0-read',
      operationIdentity: undefined
    });
  });
});
