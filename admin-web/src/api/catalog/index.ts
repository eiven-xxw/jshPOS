import request from '@/utils/request';
import type { AxiosPromise } from 'axios';
import { assertMinorAmount, assertPositiveExactInteger, CATALOG_ENDPOINTS, trustedCatalogPayload } from './contract';
import type {
  AddPriceItemForm,
  CreatePriceBookForm,
  CreateProductForm,
  DefinitionVO,
  Id64,
  ImportBatchVO,
  ImportPreflightVO,
  ImportRow,
  PackageVO,
  PriceBookVO,
  ProductState,
  ProductVO,
  ResolvedPriceVO,
  ShelfLabelPreviewVO,
  ShelfLabelTaskDetailVO,
  ShelfLabelTaskItemVO,
  ShelfLabelTaskState,
  ShelfLabelTaskVO,
  ShelfLabelTemplateState,
  ShelfLabelTemplateVO
} from './types';

export { CATALOG_ENDPOINTS } from './contract';

export const createCategory = (data: { parentId?: Id64; code: string; name: string; sortNo: number }): AxiosPromise<DefinitionVO> =>
  request({ url: `${CATALOG_ENDPOINTS.root}/categories`, method: 'post', data: trustedCatalogPayload(data) });

export const createBrand = (data: { code: string; name: string }): AxiosPromise<DefinitionVO> =>
  request({ url: `${CATALOG_ENDPOINTS.root}/brands`, method: 'post', data: trustedCatalogPayload(data) });

export const createUnit = (data: { code: string; name: string; decimalScale: number }): AxiosPromise<DefinitionVO> =>
  request({ url: `${CATALOG_ENDPOINTS.root}/units`, method: 'post', data: trustedCatalogPayload(data) });

export const listProducts = (status?: ProductState, limit = 100): AxiosPromise<ProductVO[]> =>
  request({ url: CATALOG_ENDPOINTS.products, method: 'get', params: { status, limit } });

export const createProduct = (data: CreateProductForm): AxiosPromise<ProductVO> => {
  data.units.forEach((unit) => {
    assertPositiveExactInteger(unit.ratioNumerator, 'ratioNumerator');
    assertPositiveExactInteger(unit.ratioDenominator, 'ratioDenominator');
  });
  return request({ url: CATALOG_ENDPOINTS.products, method: 'post', data: trustedCatalogPayload(data) });
};

export const changeProductState = (skuId: Id64, state: ProductState, version: number): AxiosPromise<ProductVO> =>
  request({ url: `${CATALOG_ENDPOINTS.products}/${skuId}/state`, method: 'put', data: trustedCatalogPayload({ state, version }) });

export const preflightImport = (idempotencyKey: string, rows: ImportRow[]): AxiosPromise<ImportPreflightVO> =>
  request({ url: `${CATALOG_ENDPOINTS.imports}/preflight`, method: 'post', data: trustedCatalogPayload({ idempotencyKey, rows }) });

export const publishImport = (batchId: Id64): AxiosPromise<ImportBatchVO> =>
  request({ url: `${CATALOG_ENDPOINTS.imports}/${batchId}/publish`, method: 'post' });

export const rollbackImport = (batchId: Id64): AxiosPromise<ImportBatchVO> =>
  request({ url: `${CATALOG_ENDPOINTS.imports}/${batchId}/rollback`, method: 'post' });

export const createPriceBook = (data: CreatePriceBookForm): AxiosPromise<PriceBookVO> =>
  request({ url: CATALOG_ENDPOINTS.priceBooks, method: 'post', data: trustedCatalogPayload(data) });

export const addPriceItem = (bookId: Id64, data: AddPriceItemForm): AxiosPromise<Id64> =>
  request({
    url: `${CATALOG_ENDPOINTS.priceBooks}/${bookId}/items`,
    method: 'post',
    data: trustedCatalogPayload({ ...data, amountMinor: assertMinorAmount(data.amountMinor) })
  });

export const publishPriceBook = (bookId: Id64): AxiosPromise<PriceBookVO> =>
  request({ url: `${CATALOG_ENDPOINTS.priceBooks}/${bookId}/publish`, method: 'post' });

export const retirePriceBook = (bookId: Id64): AxiosPromise<PriceBookVO> =>
  request({ url: `${CATALOG_ENDPOINTS.priceBooks}/${bookId}/retire`, method: 'post' });

export const resolvePrice = (skuId: Id64, unitId: Id64, storeId: Id64, at?: string): AxiosPromise<ResolvedPriceVO> =>
  request({ url: `${CATALOG_ENDPOINTS.priceBooks}/resolve`, method: 'get', params: { skuId, unitId, storeId, at } });

export const publishPackage = (storeId: Id64, packageVersion: number, previousVersion: number): AxiosPromise<PackageVO> =>
  request({
    url: CATALOG_ENDPOINTS.packages,
    method: 'post',
    data: trustedCatalogPayload({ storeId, packageVersion, previousVersion })
  });

export const latestPackage = (storeId: Id64): AxiosPromise<PackageVO> =>
  request({ url: `${CATALOG_ENDPOINTS.packages}/latest`, method: 'get', params: { storeId } });

export const listShelfLabelTemplates = (state?: ShelfLabelTemplateState): AxiosPromise<ShelfLabelTemplateVO[]> =>
  request({ url: `${CATALOG_ENDPOINTS.shelfLabels}/templates`, method: 'get', params: { state, limit: 200 } });

export const createShelfLabelTemplate = (data: {
  templateCode: string;
  templateName: string;
  versionNo: number;
  scopeType: 'TENANT' | 'STORE';
  storeId?: Id64;
  bodyTemplate: string;
  idempotencyKey: string;
  correlationId: string;
}): AxiosPromise<ShelfLabelTemplateVO> =>
  request({ url: `${CATALOG_ENDPOINTS.shelfLabels}/templates`, method: 'post', data: trustedCatalogPayload(data) });

export const publishShelfLabelTemplate = (
  templateId: Id64,
  expectedVersion: number,
  identity: { idempotencyKey: string; correlationId: string }
): AxiosPromise<ShelfLabelTemplateVO> =>
  request({
    url: `${CATALOG_ENDPOINTS.shelfLabels}/templates/${templateId}/publish`,
    method: 'post',
    data: trustedCatalogPayload({ expectedVersion, ...identity })
  });

export const retireShelfLabelTemplate = (
  templateId: Id64,
  expectedVersion: number,
  identity: { idempotencyKey: string; correlationId: string }
): AxiosPromise<ShelfLabelTemplateVO> =>
  request({
    url: `${CATALOG_ENDPOINTS.shelfLabels}/templates/${templateId}/retire`,
    method: 'post',
    data: trustedCatalogPayload({ expectedVersion, ...identity })
  });

export const listShelfLabelTasks = (storeId?: Id64, state?: ShelfLabelTaskState): AxiosPromise<ShelfLabelTaskVO[]> =>
  request({ url: `${CATALOG_ENDPOINTS.shelfLabels}/tasks`, method: 'get', params: { storeId, state, limit: 200 } });

export const getShelfLabelTask = (taskId: Id64): AxiosPromise<ShelfLabelTaskDetailVO> =>
  request({ url: `${CATALOG_ENDPOINTS.shelfLabels}/tasks/${taskId}`, method: 'get' });

export const previewShelfLabelItem = (
  itemId: Id64,
  templateId: Id64 | undefined,
  identity: { idempotencyKey: string; correlationId: string }
): AxiosPromise<ShelfLabelPreviewVO> =>
  request({
    url: `${CATALOG_ENDPOINTS.shelfLabels}/items/${itemId}/preview`,
    method: 'post',
    data: trustedCatalogPayload({ templateId, ...identity })
  });

export const confirmShelfLabelReplacement = (
  itemId: Id64,
  expectedVersion: number,
  reason: string,
  identity: { idempotencyKey: string; correlationId: string }
): AxiosPromise<ShelfLabelTaskItemVO> =>
  request({
    url: `${CATALOG_ENDPOINTS.shelfLabels}/items/${itemId}/confirm`,
    method: 'post',
    data: trustedCatalogPayload({ expectedVersion, reason, ...identity })
  });

export const recordShelfLabelException = (
  itemId: Id64,
  expectedVersion: number,
  reason: string,
  identity: { idempotencyKey: string; correlationId: string }
): AxiosPromise<ShelfLabelTaskItemVO> =>
  request({
    url: `${CATALOG_ENDPOINTS.shelfLabels}/items/${itemId}/exceptions`,
    method: 'post',
    data: trustedCatalogPayload({ expectedVersion, reason, ...identity })
  });

export const dispatchShelfLabelTask = (
  taskId: Id64,
  expectedVersion: number,
  previewSha256: string,
  identity: { idempotencyKey: string; correlationId: string }
): AxiosPromise<ShelfLabelTaskVO> =>
  request({
    url: `${CATALOG_ENDPOINTS.shelfLabels}/tasks/${taskId}/dispatch`,
    method: 'post',
    data: trustedCatalogPayload({ expectedVersion, previewSha256, ...identity })
  });
