import request from '@/utils/request';
import type { AxiosPromise } from 'axios';
import { SERVICE_ENDPOINT, serviceIdentityValue, serviceUlid, trustedServicePayload } from './contract';
import type {
  AttachmentDownload,
  AttachmentRecord,
  CatalogDetail,
  CatalogItemInput,
  ProjectDetail,
  ProjectRecord,
  ServiceIdentity,
  TicketDetail,
  TicketRecord
} from './types';

const headers = (identity: ServiceIdentity, expectedVersion?: number) => ({
  'Idempotency-Key': serviceIdentityValue(identity.idempotencyKey),
  'X-Correlation-ID': serviceIdentityValue(identity.correlationId),
  ...(expectedVersion === undefined ? {} : { 'If-Match-Version': expectedVersion })
});

export const createServiceCatalog = (
  data: { catalogCode: string; versionNo: number; industryTemplate: string; name: string; items: CatalogItemInput[] },
  identity: ServiceIdentity
): AxiosPromise<CatalogDetail> =>
  request({ url: `${SERVICE_ENDPOINT}/catalogs`, method: 'post', headers: headers(identity), data: trustedServicePayload(data) });
export const publishServiceCatalog = (catalogId: string, identity: ServiceIdentity): AxiosPromise<CatalogDetail> =>
  request({ url: `${SERVICE_ENDPOINT}/catalogs/${serviceUlid(catalogId)}/publish`, method: 'post', headers: headers(identity) });

export const listServiceProjects = (storeId: number): AxiosPromise<ProjectRecord[]> =>
  request({ url: `${SERVICE_ENDPOINT}/projects`, method: 'get', params: { storeId, limit: 100 } });
export const getServiceProject = (projectId: string): AxiosPromise<ProjectDetail> =>
  request({ url: `${SERVICE_ENDPOINT}/projects/${serviceUlid(projectId)}`, method: 'get' });
export const createServiceProject = (
  data: { storeId: number; catalogId: string; targetDate: string; ownerUserId?: number },
  identity: ServiceIdentity
): AxiosPromise<ProjectDetail> =>
  request({ url: `${SERVICE_ENDPOINT}/projects`, method: 'post', headers: headers(identity), data: trustedServicePayload(data) });
export const commandServiceProject = (
  projectId: string,
  expectedVersion: number,
  data: { command: string; reason: string },
  identity: ServiceIdentity
): AxiosPromise<ProjectDetail> =>
  request({
    url: `${SERVICE_ENDPOINT}/projects/${serviceUlid(projectId)}/commands`,
    method: 'post',
    headers: headers(identity, expectedVersion),
    data: trustedServicePayload(data)
  });
export const completeServiceProjectCheck = (
  projectId: string,
  checkId: string,
  expectedVersion: number,
  reason: string,
  identity: ServiceIdentity
): AxiosPromise<ProjectDetail> =>
  request({
    url: `${SERVICE_ENDPOINT}/projects/${serviceUlid(projectId)}/checks/${serviceUlid(checkId)}/complete`,
    method: 'post',
    headers: headers(identity, expectedVersion),
    data: trustedServicePayload({ reason })
  });

export const listServiceTickets = (storeId: number, state?: string): AxiosPromise<TicketRecord[]> =>
  request({ url: `${SERVICE_ENDPOINT}/tickets`, method: 'get', params: { storeId, state, limit: 100 } });
export const getServiceTicket = (ticketId: string): AxiosPromise<TicketDetail> =>
  request({ url: `${SERVICE_ENDPOINT}/tickets/${serviceUlid(ticketId)}`, method: 'get' });
export const createServiceTicket = (
  data: {
    storeId: number;
    projectId?: string;
    serviceType: string;
    priority: string;
    subject: string;
    description?: string;
    internalTargetMinutes: number;
  },
  identity: ServiceIdentity
): AxiosPromise<TicketDetail> =>
  request({ url: `${SERVICE_ENDPOINT}/tickets`, method: 'post', headers: headers(identity), data: trustedServicePayload(data) });
export const commandServiceTicket = (
  ticketId: string,
  expectedVersion: number,
  data: { command: string; assigneeUserId?: number; leaseMinutes?: number; reason: string; resolutionSummary?: string },
  identity: ServiceIdentity
): AxiosPromise<TicketDetail> =>
  request({
    url: `${SERVICE_ENDPOINT}/tickets/${serviceUlid(ticketId)}/commands`,
    method: 'post',
    headers: headers(identity, expectedVersion),
    data: trustedServicePayload(data)
  });
export const uploadServiceAttachment = (ticketId: string, file: File, identity: ServiceIdentity): AxiosPromise<AttachmentRecord> => {
  const body = new FormData();
  body.append('file', file);
  return request({ url: `${SERVICE_ENDPOINT}/tickets/${serviceUlid(ticketId)}/attachments`, method: 'post', headers: headers(identity), data: body });
};
export const issueServiceAttachmentDownload = (ticketId: string, attachmentId: string): AxiosPromise<AttachmentDownload> =>
  request({ url: `${SERVICE_ENDPOINT}/tickets/${serviceUlid(ticketId)}/attachments/${serviceUlid(attachmentId)}/download`, method: 'post' });
export const cleanupServiceAttachment = (ticketId: string, attachmentId: string, identity: ServiceIdentity): AxiosPromise<AttachmentRecord> =>
  request({
    url: `${SERVICE_ENDPOINT}/tickets/${serviceUlid(ticketId)}/attachments/${serviceUlid(attachmentId)}/cleanup`,
    method: 'post',
    headers: headers(identity)
  });

export type { ServiceIdentity } from './types';
