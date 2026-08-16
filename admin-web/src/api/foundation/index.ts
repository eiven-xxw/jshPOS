import request from '@/utils/request';
import type { AxiosPromise } from 'axios';
import { assertNoClientTenantOverride, FOUNDATION_ENDPOINTS } from './contract';
export { FOUNDATION_ENDPOINTS } from './contract';
import type {
  ActivateConfigForm,
  AuditEventVO,
  BusinessDateVO,
  ConfigBindingVO,
  ConfigTemplateVO,
  ConfigVersionVO,
  CreateConfigTemplateForm,
  CreateConfigVersionForm,
  CreateOrgUnitForm,
  CreateStoreForm,
  OrgUnitVO,
  StaffScopeInput,
  StaffScopeVO,
  StoreVO,
  UpdateOrgUnitForm,
  UpdateStoreForm
} from './types';

const trustedPayload = <T>(data: T): T => {
  assertNoClientTenantOverride(data);
  return data;
};

export const listOrgUnits = (): AxiosPromise<OrgUnitVO[]> => request({ url: FOUNDATION_ENDPOINTS.orgUnits, method: 'get' });

export const createOrgUnit = (data: CreateOrgUnitForm): AxiosPromise<OrgUnitVO> =>
  request({ url: FOUNDATION_ENDPOINTS.orgUnits, method: 'post', data: trustedPayload(data) });

export const updateOrgUnit = (orgUnitId: number, data: UpdateOrgUnitForm): AxiosPromise<OrgUnitVO> =>
  request({ url: `${FOUNDATION_ENDPOINTS.orgUnits}/${orgUnitId}`, method: 'put', data: trustedPayload(data) });

export const listStores = (): AxiosPromise<StoreVO[]> => request({ url: FOUNDATION_ENDPOINTS.stores, method: 'get' });

export const createStore = (data: CreateStoreForm): AxiosPromise<StoreVO> =>
  request({ url: FOUNDATION_ENDPOINTS.stores, method: 'post', data: trustedPayload(data) });

export const updateStore = (storeId: number, data: UpdateStoreForm): AxiosPromise<StoreVO> =>
  request({ url: `${FOUNDATION_ENDPOINTS.stores}/${storeId}`, method: 'put', data: trustedPayload(data) });

export const getBusinessDate = (storeId: number, at?: string): AxiosPromise<BusinessDateVO> =>
  request({ url: `${FOUNDATION_ENDPOINTS.stores}/${storeId}/business-date`, method: 'get', params: { at } });

export const listStaffScopes = (userId: number): AxiosPromise<StaffScopeVO[]> =>
  request({ url: `${FOUNDATION_ENDPOINTS.staffScopes}/${userId}`, method: 'get' });

export const replaceStaffScopes = (userId: number, scopes: StaffScopeInput[]): AxiosPromise<StaffScopeVO[]> =>
  request({ url: `${FOUNDATION_ENDPOINTS.staffScopes}/${userId}`, method: 'put', data: trustedPayload({ scopes }) });

export const listConfigTemplates = (): AxiosPromise<ConfigTemplateVO[]> =>
  request({ url: `${FOUNDATION_ENDPOINTS.config}/templates`, method: 'get' });

export const createConfigTemplate = (data: CreateConfigTemplateForm): AxiosPromise<ConfigTemplateVO> =>
  request({ url: `${FOUNDATION_ENDPOINTS.config}/templates`, method: 'post', data: trustedPayload(data) });

export const createConfigVersion = (templateId: number, data: CreateConfigVersionForm): AxiosPromise<ConfigVersionVO> =>
  request({ url: `${FOUNDATION_ENDPOINTS.config}/templates/${templateId}/versions`, method: 'post', data: trustedPayload(data) });

export const publishConfigVersion = (versionId: number): AxiosPromise<ConfigVersionVO> =>
  request({ url: `${FOUNDATION_ENDPOINTS.config}/versions/${versionId}/publish`, method: 'post' });

export const activateConfig = (data: ActivateConfigForm): AxiosPromise<ConfigBindingVO> =>
  request({ url: `${FOUNDATION_ENDPOINTS.config}/bindings/activate`, method: 'post', data: trustedPayload(data) });

export const rollbackConfig = (bindingId: number): AxiosPromise<ConfigBindingVO> =>
  request({ url: `${FOUNDATION_ENDPOINTS.config}/bindings/${bindingId}/rollback`, method: 'post' });

export const listAuditEvents = (occurredBefore?: string, limit = 100): AxiosPromise<AuditEventVO[]> =>
  request({ url: FOUNDATION_ENDPOINTS.auditEvents, method: 'get', params: { occurredBefore, limit } });
