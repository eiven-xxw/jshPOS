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
