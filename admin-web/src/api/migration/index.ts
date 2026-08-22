import request from '@/utils/request';
import type { AxiosPromise } from 'axios';
import { MIGRATION_ENDPOINT, migrationUlid, trustedMigrationPayload } from './contract';
import type { MigrationBatchDetail, MigrationBatchView, MigrationDataType, MigrationPreflightErrorPage, MigrationReconciliation } from './types';

export const createMigrationBatch = (data: {
  dataTypes: MigrationDataType[];
  idempotencyKey: string;
  correlationId: string;
}): AxiosPromise<MigrationBatchView> => request({ url: MIGRATION_ENDPOINT, method: 'post', data: trustedMigrationPayload(data) });

export const getMigrationBatch = (batchId: string): AxiosPromise<MigrationBatchDetail> =>
  request({ url: `${MIGRATION_ENDPOINT}/${migrationUlid(batchId)}`, method: 'get' });

export const getMigrationErrors = (batchId: string, page: number, pageSize: number): AxiosPromise<MigrationPreflightErrorPage> =>
  request({ url: `${MIGRATION_ENDPOINT}/${migrationUlid(batchId)}/errors`, method: 'get', params: { page, pageSize } });

export const uploadMigrationFile = (batchId: string, metadata: Record<string, unknown>, file: File): AxiosPromise<unknown> => {
  trustedMigrationPayload(metadata);
  const form = new FormData();
  form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }));
  form.append('file', file, file.name);
  return request({ url: `${MIGRATION_ENDPOINT}/${migrationUlid(batchId)}/files`, method: 'post', data: form });
};

const action = <T>(batchId: string, name: string, data: Record<string, unknown>): AxiosPromise<T> =>
  request({
    url: `${MIGRATION_ENDPOINT}/${migrationUlid(batchId)}/${name}`,
    method: 'post',
    data: trustedMigrationPayload(data)
  });

export const approveMigration = (batchId: string, data: Record<string, unknown>): AxiosPromise<MigrationBatchDetail> =>
  action(batchId, 'approvals', data);
export const resumeMigration = (batchId: string, data: Record<string, unknown>): AxiosPromise<MigrationBatchDetail> =>
  action(batchId, 'resume', data);
export const reconcileMigration = (batchId: string, data: Record<string, unknown>): AxiosPromise<MigrationReconciliation> =>
  action(batchId, 'reconcile', data);
export const activateMigration = (batchId: string, data: Record<string, unknown>): AxiosPromise<MigrationBatchDetail> =>
  action(batchId, 'activate', data);
export const cleanupMigration = (batchId: string, data: Record<string, unknown>): AxiosPromise<MigrationBatchDetail> =>
  action(batchId, 'cleanup', data);
