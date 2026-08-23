import request from '@/utils/request';
import type { AxiosPromise } from 'axios';
import { EXCEPTION_CENTER_ENDPOINT, exceptionCaseId, trustedExceptionPayload } from './contract';
import type { ExceptionCaseDetail, ExceptionCaseRecord } from './types';

export interface ExceptionIdentity {
  idempotencyKey: string;
  correlationId: string;
}
const headers = (value: ExceptionIdentity) => ({ 'Idempotency-Key': value.idempotencyKey, 'X-Correlation-ID': value.correlationId });

export const listExceptionCases = (params: {
  storeId: number;
  state?: string;
  severity?: string;
  limit?: number;
}): AxiosPromise<ExceptionCaseRecord[]> => request({ url: EXCEPTION_CENTER_ENDPOINT, method: 'get', params });
export const getExceptionCase = (id: string): AxiosPromise<ExceptionCaseDetail> =>
  request({ url: `${EXCEPTION_CENTER_ENDPOINT}/${exceptionCaseId(id)}`, method: 'get' });
export const scanExceptionOwners = (
  data: { storeId: number; businessDate: string },
  identity: ExceptionIdentity
): AxiosPromise<ExceptionCaseRecord[]> =>
  request({ url: `${EXCEPTION_CENTER_ENDPOINT}/scan`, method: 'post', headers: headers(identity), data: trustedExceptionPayload(data) });

const action = (id: string, name: string, data: Record<string, unknown>, identity: ExceptionIdentity): AxiosPromise<ExceptionCaseDetail> =>
  request({
    url: `${EXCEPTION_CENTER_ENDPOINT}/${exceptionCaseId(id)}/${name}`,
    method: 'post',
    headers: headers(identity),
    data: trustedExceptionPayload(data)
  });
export const claimExceptionCase = (id: string, leaseMinutes: number, identity: ExceptionIdentity) => action(id, 'claim', { leaseMinutes }, identity);
export const startExceptionCase = (id: string, reason: string, identity: ExceptionIdentity) => action(id, 'start', { reason }, identity);
export const transferExceptionCase = (id: string, assigneeUserId: number, leaseMinutes: number, reason: string, identity: ExceptionIdentity) =>
  action(id, 'transfer', { assigneeUserId, leaseMinutes, reason }, identity);
export const planExceptionRepair = (id: string, actionCode: string, planSummary: string, identity: ExceptionIdentity) =>
  action(id, 'plan', { actionCode, planSummary }, identity);
export const executeExceptionRepair = (id: string, actionCode: string, identity: ExceptionIdentity) => action(id, 'repair', { actionCode }, identity);
export const reviewExceptionCase = (id: string, reason: string, identity: ExceptionIdentity) => action(id, 'review', { reason }, identity);
export const closeExceptionCase = (id: string, reason: string, identity: ExceptionIdentity) => action(id, 'close', { reason }, identity);
export const reopenExceptionCase = (id: string, reason: string, identity: ExceptionIdentity) => action(id, 'reopen', { reason }, identity);
