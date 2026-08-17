import request from '@/utils/request';
import type { AxiosPromise } from 'axios';
import { REPORTING_ENDPOINTS, trustedReportingPayload } from './contract';
import type { DownloadTokenVO, ExportRequest, ExportVO, InventoryCostDailyVO, ReportQuery, SalesDailyVO } from './types';

export { REPORTING_ENDPOINTS } from './contract';

export const querySalesDaily = (params: ReportQuery): AxiosPromise<SalesDailyVO[]> =>
  request({ url: REPORTING_ENDPOINTS.salesDaily, method: 'get', params: trustedReportingPayload(params) });

export const queryInventoryCostDaily = (params: ReportQuery): AxiosPromise<InventoryCostDailyVO[]> =>
  request({ url: REPORTING_ENDPOINTS.inventoryCostDaily, method: 'get', params: trustedReportingPayload(params) });

export const requestReportExport = (data: ExportRequest): AxiosPromise<ExportVO> =>
  request({ url: REPORTING_ENDPOINTS.exports, method: 'post', data: trustedReportingPayload(data) });

export const getReportExport = (exportId: string): AxiosPromise<ExportVO> =>
  request({ url: `${REPORTING_ENDPOINTS.exports}/${exportId}`, method: 'get' });

export const approveReportExport = (
  exportId: string,
  approved: boolean,
  reason: string,
  expectedVersion: number,
  correlationId: string
): AxiosPromise<ExportVO> =>
  request({
    url: `${REPORTING_ENDPOINTS.exports}/${exportId}/approve`,
    method: 'post',
    data: trustedReportingPayload({ approved, reason, expectedVersion, correlationId })
  });

export const generateReportExport = (exportId: string, expectedVersion: number, correlationId: string): AxiosPromise<ExportVO> =>
  request({
    url: `${REPORTING_ENDPOINTS.exports}/${exportId}/generate`,
    method: 'post',
    data: trustedReportingPayload({ expectedVersion, correlationId })
  });

export const issueReportDownloadToken = (exportId: string): AxiosPromise<DownloadTokenVO> =>
  request({ url: `${REPORTING_ENDPOINTS.exports}/${exportId}/download-token`, method: 'post' });

export const downloadReportExport = (exportId: string, token: string): AxiosPromise<Blob> =>
  request({ url: `${REPORTING_ENDPOINTS.exports}/${exportId}/download`, method: 'get', params: { token }, responseType: 'blob' });
