export type Id64 = string | number;
export type ProductType = 'STANDARD' | 'WEIGHT' | 'COUNT';
export type ProductState = 'DRAFT' | 'ACTIVE' | 'INACTIVE';
export type PriceScope = 'TENANT_BASE' | 'STORE';

export interface DefinitionVO {
  id: Id64;
  code: string;
  name: string;
  status: 'ACTIVE' | 'INACTIVE';
}

export interface ProductVO {
  skuId: Id64;
  spuId: Id64;
  spuCode: string;
  skuCode: string;
  name: string;
  categoryId: Id64;
  brandId?: Id64;
  productType: ProductType;
  status: ProductState;
  version: number;
}

export interface UnitInput {
  unitId: Id64;
  ratioNumerator: number;
  ratioDenominator: number;
  primary: boolean;
  barcodes: string[];
}

export interface CreateProductForm {
  spuCode: string;
  skuCode: string;
  name: string;
  categoryId: Id64;
  brandId?: Id64;
  productType: ProductType;
  attributes: Record<string, unknown>;
  units: UnitInput[];
}

export interface ImportRow {
  rowNumber: number;
  spuCode: string;
  skuCode: string;
  name: string;
  categoryCode: string;
  brandCode: string;
  productType: ProductType;
  baseUnitCode: string;
  quantity: string;
  ratioNumerator: number;
  ratioDenominator: number;
  barcodes: string[];
}

export interface ImportBatchVO {
  importBatchId: Id64;
  idempotencyKey: string;
  payloadSha256: string;
  rowCount: number;
  errorCount: number;
  state: 'PRECHECKED' | 'REJECTED' | 'PUBLISHED' | 'ROLLED_BACK';
  previousBatchId?: Id64;
}

export interface ImportPreflightVO {
  batch: ImportBatchVO;
  errors: Array<{ rowNumber: number; field: string; message: string }>;
}

export interface CreatePriceBookForm {
  code: string;
  name: string;
  versionNo: number;
  scopeType: PriceScope;
  storeId?: Id64;
}

export interface AddPriceItemForm {
  skuId: Id64;
  unitId: Id64;
  amountMinor: number;
  effectiveFrom: string;
  effectiveTo?: string;
}

export interface PriceBookVO extends CreatePriceBookForm {
  priceBookId: Id64;
  state: 'DRAFT' | 'PUBLISHED' | 'RETIRED';
  contentSha256?: string;
}

export interface ResolvedPriceVO {
  amountMinor: number;
  currency: 'CNY';
  priceBookId: Id64;
  priceItemId: Id64;
  scopeType: PriceScope;
  effectiveFrom: string;
}

export interface PackageVO {
  packageId: Id64;
  storeId: Id64;
  packageVersion: Id64;
  previousVersion: Id64;
  schemaVersion: string;
  payloadSha256: string;
  signatureAlgorithm: 'Ed25519';
  signingKeyId: string;
  objectKey: string;
  recordCount: number;
  generatedAt: string;
}

export type ShelfLabelTemplateState = 'DRAFT' | 'PUBLISHED' | 'RETIRED';
export type ShelfLabelItemState = 'PENDING' | 'PREVIEW_READY' | 'REPLACED_CONFIRMED' | 'EXCEPTION' | 'SUPERSEDED';
export type ShelfLabelTaskState = 'PENDING' | 'PREVIEW_READY' | 'IN_PROGRESS' | 'COMPLETED' | 'EXCEPTION' | 'SUPERSEDED' | 'DISPATCH_BLOCKED';

export interface ShelfLabelTemplateVO {
  templateId: Id64;
  templateCode: string;
  templateName: string;
  versionNo: number;
  scopeType: 'TENANT' | 'STORE';
  storeId?: Id64;
  bodyTemplate: string;
  state: ShelfLabelTemplateState;
  contentSha256?: string;
  publishedAt?: string;
  version: number;
}

export interface ShelfLabelTaskVO {
  taskId: Id64;
  sourceEventKey: string;
  sourceEventType: 'PRICE_BOOK_PUBLISHED' | 'PRICE_BOOK_RETIRED';
  sourcePriceBookId: Id64;
  sourcePriceVersion: number;
  storeId: Id64;
  storeName: string;
  effectiveAt: string;
  state: ShelfLabelTaskState;
  itemCount: number;
  pendingCount: number;
  exceptionCount: number;
  createdAt: string;
  version: number;
}

export interface ShelfLabelTaskItemVO {
  itemId: Id64;
  taskId: Id64;
  storeId: Id64;
  storeName: string;
  skuId: Id64;
  skuCode: string;
  productName: string;
  unitId: Id64;
  unitName: string;
  barcode?: string;
  oldPriceMinor?: number;
  newPriceMinor?: number;
  currency: 'CNY';
  sourcePriceVersion: number;
  effectiveAt: string;
  state: ShelfLabelItemState;
  exceptionReason?: string;
  version: number;
}

export interface ShelfLabelTaskDetailVO {
  task: ShelfLabelTaskVO;
  items: ShelfLabelTaskItemVO[];
}

export interface ShelfLabelPreviewVO {
  templateId: Id64;
  templateVersion: number;
  templateSha256: string;
  item: ShelfLabelTaskItemVO;
  renderedText: string;
  previewSha256: string;
}
