import { ElMessage, ElMessageBox } from 'element-plus';
import { newOperationCommandId } from '@/api/operations';
import type { OwnerOperationView } from '@/api/operations/types';
import { buildOperationConfirmation, createSingleFlight, type OperationPageState } from './model';

export interface ControlledOperationInput<T extends OwnerOperationView> {
  owner: string;
  objectId: string;
  currentState: string;
  currentVersion: number;
  action: string;
  impact: string;
  reason: string;
  execute: (idempotencyKey: string) => Promise<{ data: T }>;
}

/**
 * Gate 6E 后台统一写操作编排：二次确认、单航班、失败保留原幂等键、服务端结果展示和关联标识记录。
 */
export const useControlledOperation = () => {
  const pageState = ref<OperationPageState>('IDLE');
  const lastError = ref('');
  const lastCorrelationId = ref('');
  const retryKeys = new Map<string, string>();
  const singleFlight = createSingleFlight();

  const retryKey = (owner: string, objectId: string, action: string): string => {
    const identity = `${owner}:${objectId}:${action}`;
    const existing = retryKeys.get(identity);
    if (existing) return existing;
    const created = newOperationCommandId();
    retryKeys.set(identity, created);
    return created;
  };

  const runRead = async <T>(work: () => Promise<{ data: T }>): Promise<T> => {
    pageState.value = 'LOADING';
    lastError.value = '';
    try {
      const response = await work();
      pageState.value = 'READY';
      return response.data;
    } catch (error) {
      pageState.value = 'FAILED';
      lastError.value = error instanceof Error ? error.message : '查询失败，请使用关联标识联系管理员';
      throw error;
    }
  };

  const runControlled = async <T extends OwnerOperationView>(input: ControlledOperationInput<T>): Promise<T | undefined> => {
    const identity = `${input.owner}:${input.objectId}:${input.action}`;
    const idempotencyKey = retryKey(input.owner, input.objectId, input.action);
    const snapshot = buildOperationConfirmation({ ...input, idempotencyKey });
    pageState.value = 'CONFIRMING';
    try {
      await ElMessageBox.confirm(
        `对象：${snapshot.objectId}\n当前状态/版本：${snapshot.currentState} / ${snapshot.currentVersion}\n动作：${snapshot.action}\n影响：${snapshot.impact}\n原因：${snapshot.reason}\n幂等键：${snapshot.idempotencyKey}`,
        `${snapshot.owner} 高风险操作确认`,
        { type: 'warning', confirmButtonText: '确认执行', cancelButtonText: '取消' }
      );
    } catch {
      pageState.value = 'READY';
      return undefined;
    }

    return singleFlight(async () => {
      pageState.value = 'SUBMITTING';
      lastError.value = '';
      try {
        const response = await input.execute(idempotencyKey);
        pageState.value = 'SUCCEEDED';
        lastCorrelationId.value = String(response.data.correlationId || idempotencyKey);
        retryKeys.delete(identity);
        ElMessage.success(`操作已由服务端确认，关联标识：${lastCorrelationId.value}`);
        return response.data;
      } catch (error: unknown) {
        const status = (error as { response?: { status?: number } })?.response?.status;
        pageState.value = status === 409 ? 'STALE' : 'FAILED';
        lastError.value = error instanceof Error ? error.message : '结果未知，请复用原幂等键查询或重试';
        ElMessage.error(`${lastError.value}；原幂等键已保留：${idempotencyKey}`);
        throw error;
      }
    });
  };

  return { pageState, lastError, lastCorrelationId, runRead, runControlled };
};
