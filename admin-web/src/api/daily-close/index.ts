import request from '@/utils/request';
import type { AxiosPromise } from 'axios';
import { DAILY_CLOSE_ENDPOINT, dailyCloseUlid, trustedDailyClosePayload } from './contract';
import type { DailyCloseDetail, DailyCloseRecord } from './types';

export interface DailyCloseIdentity {
  idempotencyKey: string;
  correlationId: string;
}

const headers = (identity: DailyCloseIdentity) => ({
  'Idempotency-Key': identity.idempotencyKey,
  'X-Correlation-ID': identity.correlationId
});

export const createDailyClose = (
  data: { storeId: number; businessDate: string; correctionOfCloseId?: string; correctionReason?: string },
  identity: DailyCloseIdentity
): AxiosPromise<DailyCloseDetail> =>
  request({ url: DAILY_CLOSE_ENDPOINT, method: 'post', headers: headers(identity), data: trustedDailyClosePayload(data) });

export const listDailyCloses = (params: { storeId: number; businessDate?: string; limit?: number }): AxiosPromise<DailyCloseRecord[]> =>
  request({ url: DAILY_CLOSE_ENDPOINT, method: 'get', params });

export const getDailyClose = (closeId: string): AxiosPromise<DailyCloseDetail> =>
  request({ url: `${DAILY_CLOSE_ENDPOINT}/${dailyCloseUlid(closeId)}`, method: 'get' });

const action = (closeId: string, name: string, identity: DailyCloseIdentity, data?: Record<string, unknown>): AxiosPromise<DailyCloseDetail> =>
  request({
    url: `${DAILY_CLOSE_ENDPOINT}/${dailyCloseUlid(closeId)}/${name}`,
    method: 'post',
    headers: headers(identity),
    data: data ? trustedDailyClosePayload(data) : undefined
  });

export const preflightDailyClose = (id: string, identity: DailyCloseIdentity) => action(id, 'preflight', identity);
export const approveDailyClose = (id: string, reason: string, identity: DailyCloseIdentity) => action(id, 'approve', identity, { reason });
export const signDailyClose = (id: string, identity: DailyCloseIdentity) => action(id, 'close', identity);
export const detectDailyCloseLateFacts = (id: string, identity: DailyCloseIdentity) => action(id, 'late-facts', identity);
