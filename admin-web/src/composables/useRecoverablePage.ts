import type { AxiosError } from 'axios';

/** ADR-071 页面状态；只描述交互过程，不复制任何 Owner 领域状态机。 */
export type RecoverablePagePhase = 'INITIAL' | 'LOADING' | 'READY' | 'EMPTY' | 'SUBMITTING' | 'FAILED' | 'UNKNOWN';

/** 可安全展示的页面错误，禁止携带响应正文、堆栈、Secret 或 PII。 */
export interface RecoverablePageError {
  code: string;
  message: string;
  correlationId: string;
  operationIdentity?: string;
}

interface ErrorEnvelope {
  code?: string | number;
  msg?: string;
  message?: string;
}

const safeText = (value: unknown, fallback: string, limit = 240): string => {
  const text = typeof value === 'string' ? value.trim() : '';
  return text && text.length <= limit ? text : fallback;
};

/**
 * 页面统一的加载、空态、安全错误与单航班编排。
 * 写失败只保留原操作身份供调用方恢复，不会自行生成或重放领域命令。
 */
export const useRecoverablePage = (defaultErrorCode: string) => {
  const phase = ref<RecoverablePagePhase>('INITIAL');
  const failure = ref<RecoverablePageError>();
  const pending = new Map<string, Promise<unknown>>();
  const unresolved = new Set<string>();
  const unresolvedFailures = new Map<string, RecoverablePageError>();

  const parseFailure = (error: unknown, operationIdentity?: string): RecoverablePageError => {
    const axios = error as AxiosError<ErrorEnvelope>;
    const body = axios.response?.data;
    const headers = axios.response?.headers as Record<string, unknown> | undefined;
    const correlation = headers?.['x-correlation-id'] ?? headers?.['X-Correlation-ID'];
    return {
      code: safeText(body?.code == null ? undefined : String(body.code), defaultErrorCode, 64),
      message: safeText(body?.msg ?? body?.message, '操作未完成，请按恢复入口刷新权威状态。'),
      correlationId: safeText(correlation, '未返回', 128),
      operationIdentity
    };
  };

  const runRead = async <T>(work: () => Promise<T>, empty: (value: T) => boolean = () => false): Promise<T | undefined> => {
    phase.value = 'LOADING';
    if (unresolved.size === 0) failure.value = undefined;
    try {
      const value = await work();
      if (unresolved.size > 0) {
        phase.value = 'UNKNOWN';
        failure.value ??= unresolvedFailures.values().next().value;
      } else {
        phase.value = empty(value) ? 'EMPTY' : 'READY';
      }
      return value;
    } catch (error) {
      phase.value = 'FAILED';
      failure.value = parseFailure(error);
      return undefined;
    }
  };

  const runWrite = <T>(operationIdentity: string, work: () => Promise<T>): Promise<T | undefined> => {
    const existing = pending.get(operationIdentity);
    if (existing) return existing as Promise<T | undefined>;
    // UNKNOWN 只能刷新/查询原事实，禁止通过再次点击生成第二个业务命令。
    if (unresolved.has(operationIdentity)) {
      phase.value = 'UNKNOWN';
      failure.value = unresolvedFailures.get(operationIdentity);
      return Promise.resolve(undefined);
    }
    phase.value = 'SUBMITTING';
    failure.value = undefined;
    const current = (async () => {
      try {
        const value = await work();
        phase.value = 'READY';
        return value;
      } catch (error) {
        const axios = error as AxiosError;
        const status = axios.response?.status;
        const unknownResult = axios.isAxiosError === true && (status == null || status >= 500);
        phase.value = unknownResult ? 'UNKNOWN' : 'FAILED';
        failure.value = parseFailure(error, operationIdentity);
        if (unknownResult) {
          unresolved.add(operationIdentity);
          unresolvedFailures.set(operationIdentity, failure.value);
        }
        return undefined;
      } finally {
        pending.delete(operationIdentity);
      }
    })();
    pending.set(operationIdentity, current);
    return current;
  };

  const submitting = computed(() => phase.value === 'SUBMITTING');
  const clearFailure = () => {
    if (failure.value?.operationIdentity && unresolved.has(failure.value.operationIdentity)) return;
    failure.value = undefined;
    if (phase.value === 'FAILED' || phase.value === 'UNKNOWN') phase.value = 'READY';
  };

  return { phase, failure, submitting, runRead, runWrite, clearFailure };
};
