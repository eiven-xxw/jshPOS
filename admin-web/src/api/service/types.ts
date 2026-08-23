export interface ServiceIdentity {
  idempotencyKey: string;
  correlationId: string;
}
export interface CatalogItemInput {
  itemCode: string;
  itemName: string;
  mandatory: boolean;
  sequenceNo: number;
}
export interface CatalogRecord {
  catalogId: string;
  tenantId: string;
  catalogCode: string;
  versionNo: number;
  industryTemplate: string;
  name: string;
  state: string;
  contentSha256: string;
  recordVersion: number;
}
export interface CatalogDetail {
  catalog: CatalogRecord;
  items: Array<CatalogItemInput & { itemId: string }>;
}
export interface ProjectRecord {
  projectId: string;
  tenantId: string;
  storeId: number;
  catalogId: string;
  state: string;
  ownerUserId: number;
  targetDate: string;
  recordVersion: number;
  contentSha256: string;
}
export interface CheckRecord {
  checkId: string;
  itemCode: string;
  itemName: string;
  mandatory: boolean;
  state: string;
  completedBy?: number;
  completedAt?: string;
  recordVersion: number;
}
export interface ProjectDetail {
  project: ProjectRecord;
  checks: CheckRecord[];
}
export interface TicketRecord {
  ticketId: string;
  tenantId: string;
  storeId: number;
  projectId?: string;
  serviceType: string;
  priority: 'P0' | 'P1' | 'P2' | 'P3';
  subject: string;
  description: string;
  state: string;
  assigneeUserId?: number;
  leaseUntil?: string;
  resolvedBy?: number;
  closedBy?: number;
  resolutionSummary?: string;
  targetAt: string;
  recordVersion: number;
  contentSha256: string;
}
export interface AttachmentRecord {
  attachmentId: string;
  fileName: string;
  mediaType: string;
  sizeBytes: number;
  sha256: string;
  state: string;
  createdAt: string;
}
export interface TicketDetail {
  ticket: TicketRecord;
  attachments: AttachmentRecord[];
  overdue: boolean;
}
export interface AttachmentDownload {
  attachment: AttachmentRecord;
  downloadUrl: string;
  expiresAt: string;
}
