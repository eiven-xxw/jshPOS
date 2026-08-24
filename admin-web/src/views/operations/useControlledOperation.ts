import { ElMessage, ElMessageBox } from 'element-plus';
import { newOperationCommandId } from '@/api/operations';
import { buildOperationConfirmation, type OperationPageState } from './model';

/** 可安全展示的 Owner 页面失败信封；不得保存响应正文、堆栈、Secret 或 PII。 */
export interface OwnerPageFailure {
  code: string;
  message: string;
  correlationId: string;
  operationIdentity?: string;
}

export interface ControlledOperationInput<T> {
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
  const pageFailure = ref<OwnerPageFailure>();
  const lastCorrelationId = ref('');
  const retryKeys = new Map<string, string>();
  const unresolved = new Map<string, OwnerPageFailure>();
  const pending = new Map<string, Promise<unknown>>();

  const safeText = (value: unknown, fallback: string, limit: number): string => {
    const text = typeof value === 'string' ? value.trim() : '';
    return text && text.length <= limit ? text : fallback;
  };

  const parseFailure = (error: unknown, operationIdentity?: string): OwnerPageFailure => {
    const response = (
      error as {
        response?: { data?: { code?: string | number; msg?: string; message?: string }; headers?: Record<string, unknown> };
      }
    )?.response;
    const correlation = response?.headers?.['x-correlation-id'] ?? response?.headers?.['X-Correlation-ID'];
    return {
      code: safeText(response?.data?.code == null ? undefined : String(response.data.code), 'OWNER_OPERATION_FAILED', 64),
      message: safeText(response?.data?.msg ?? response?.data?.message, '操作未完成，请使用关联标识查询权威状态。', 240),
      correlationId: safeText(correlation, '未返回', 128),
      operationIdentity
    };
  };

  const retryKey = (owner: string, objectId: string, action: string): string => {
    const identity = `${owner}:${objectId}:${action}`;
    const existing = retryKeys.get(identity);
    if (existing) return existing;
    const created = newOperationCommandId();
    retryKeys.set(identity, created);
    return created;
  };

  const runRead = async <T>(work: () => Promise<{ data: T }>, empty: (value: T) => boolean = () => false): Promise<T | undefined> => {
    pageState.value = 'LOADING';
    if (unresolved.size === 0) pageFailure.value = undefined;
    try {
      const response = await work();
      if (unresolved.size > 0) {
        pageState.value = 'UNKNOWN';
        pageFailure.value ??= unresolved.values().next().value;
      } else {
        pageState.value = empty(response.data) ? 'EMPTY' : 'READY';
      }
      return response.data;
    } catch (error) {
      pageState.value = 'FAILED';
      pageFailure.value = parseFailure(error);
      return undefined;
    }
  };

  const runControlled = <T>(input: ControlledOperationInput<T>): Promise<T | undefined> => {
    const identity = `${input.owner}:${input.objectId}:${input.action}`;
    const existing = pending.get(identity);
    if (existing) return existing as Promise<T | undefined>;

    const idempotencyKey = retryKey(input.owner, input.objectId, input.action);
    if (unresolved.has(identity)) {
      pageState.value = 'UNKNOWN';
      pageFailure.value = unresolved.get(identity);
      ElMessage.warning(`原操作结果仍未知，只能查询权威状态；幂等键：${idempotencyKey}`);
      return Promise.resolve(undefined);
    }

    const current = (async () => {
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

      pageState.value = 'SUBMITTING';
      pageFailure.value = undefined;
      try {
        const response = await input.execute(idempotencyKey);
        pageState.value = 'SUCCEEDED';
        const correlationId = (response.data as { correlationId?: unknown } | undefined)?.correlationId;
        lastCorrelationId.value = safeText(correlationId, idempotencyKey, 128);
        retryKeys.delete(identity);
        unresolved.delete(identity);
        ElMessage.success(`操作已由服务端确认，关联标识：${lastCorrelationId.value}`);
        return response.data;
      } catch (error: unknown) {
        const candidate = error as { isAxiosError?: boolean; response?: { status?: number } };
        const status = candidate.response?.status;
        const unknownResult = candidate.isAxiosError === true && (status == null || status >= 500);
        pageState.value = unknownResult ? 'UNKNOWN' : status === 409 ? 'STALE' : 'FAILED';
        pageFailure.value = parseFailure(error, idempotencyKey);
        if (unknownResult) unresolved.set(identity, pageFailure.value);
        ElMessage.error(`${pageFailure.value.message}；原幂等键已保留：${idempotencyKey}`);
        return undefined;
      } finally {
        pending.delete(identity);
      }
    })();
    pending.set(identity, current);
    return current;
  };

  const lastError = computed(() => pageFailure.value?.message ?? '');
  const submitting = computed(() => pageState.value === 'CONFIRMING' || pageState.value === 'SUBMITTING');

  return { pageState, pageFailure, lastError, lastCorrelationId, submitting, runRead, runControlled };
};
