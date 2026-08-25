<template>
  <div class="p-4 service-operations">
    <el-alert
      type="warning"
      :closable="false"
      show-icon
      title="Gate 8A 服务运营（内部软件执行）"
      description="所有状态、租约、职责分离、租户门店范围和附件对象键均由服务端决定；内部时间目标不构成合同 SLA。"
    />
    <OwnerPageFeedback
      surface-id="service"
      :state="phase"
      :failure="failure"
      empty-title="当前可信租户和门店范围内暂无服务记录"
      @retry="retryActive"
    />

    <el-tabs v-model="activeTab" class="mt-3" type="border-card">
      <el-tab-pane label="服务目录" name="catalog">
        <el-form :model="catalogForm" label-width="100px" class="form-grid">
          <el-form-item label="目录编码"><el-input v-model="catalogForm.catalogCode" /></el-form-item>
          <el-form-item label="版本"><el-input-number v-model="catalogForm.versionNo" :min="1" /></el-form-item>
          <el-form-item label="行业模板">
            <el-select v-model="catalogForm.industryTemplate">
              <el-option label="便利店" value="CONVENIENCE" />
              <el-option label="零食折扣店" value="SNACK_DISCOUNT" />
              <el-option label="社区超市" value="COMMUNITY_SUPERMARKET" />
            </el-select>
          </el-form-item>
          <el-form-item label="目录名称"><el-input v-model="catalogForm.name" /></el-form-item>
        </el-form>
        <el-table :data="catalogForm.items" border>
          <el-table-column label="编码"
            ><template #default="scope"><el-input v-model="scope.row.itemCode" /></template
          ></el-table-column>
          <el-table-column label="检查项"
            ><template #default="scope"><el-input v-model="scope.row.itemName" /></template
          ></el-table-column>
          <el-table-column label="必选" width="100"
            ><template #default="scope"><el-switch v-model="scope.row.mandatory" /></template
          ></el-table-column>
          <el-table-column prop="sequenceNo" label="顺序" width="90" />
        </el-table>
        <div class="toolbar">
          <el-button v-hasPermi="['service:catalog:manage']" type="primary" :loading="loading" @click="createCatalog">创建草稿</el-button>
          <el-button
            v-if="catalogDetail?.catalog.state === 'DRAFT'"
            v-hasPermi="['service:catalog:manage']"
            :loading="loading"
            @click="publishCatalog"
            >发布不可变版本</el-button
          >
        </div>
        <el-descriptions v-if="catalogDetail" :column="3" border>
          <el-descriptions-item label="目录 ULID">{{ catalogDetail.catalog.catalogId }}</el-descriptions-item>
          <el-descriptions-item label="状态"
            ><el-tag>{{ catalogDetail.catalog.state }}</el-tag></el-descriptions-item
          >
          <el-descriptions-item label="摘要"
            ><code>{{ catalogDetail.catalog.contentSha256 }}</code></el-descriptions-item
          >
        </el-descriptions>
      </el-tab-pane>

      <el-tab-pane label="实施项目" name="project">
        <el-form :inline="true">
          <el-form-item label="门店"><el-input-number v-model="storeId" :min="1" /></el-form-item>
          <el-form-item label="目录 ULID"><el-input v-model="projectForm.catalogId" class="ulid" /></el-form-item>
          <el-form-item label="目标日期"><el-date-picker v-model="projectForm.targetDate" type="date" value-format="YYYY-MM-DD" /></el-form-item>
          <el-form-item>
            <el-button v-hasPermi="['service:project:create']" type="primary" :loading="loading" @click="createProject">创建项目</el-button>
            <el-button v-hasPermi="['service:project:read']" data-testid="service-project-refresh" :loading="loading" @click="refreshProjects">刷新</el-button>
          </el-form-item>
        </el-form>
        <el-table :data="projects" border @row-click="openProject">
          <el-table-column prop="projectId" label="项目 ULID" min-width="220" />
          <el-table-column prop="state" label="状态" width="170" />
          <el-table-column prop="ownerUserId" label="负责人" width="100" />
          <el-table-column prop="targetDate" label="内部目标日期" width="140" />
          <el-table-column prop="recordVersion" label="版本" width="80" />
        </el-table>
        <template v-if="projectDetail">
          <div class="toolbar">
            <el-input v-model="actionReason" placeholder="受审计的操作原因" class="reason" />
            <el-button
              v-for="command in projectCommands"
              :key="command"
              v-hasPermi="['service:project:operate']"
              @click="runProjectCommand(command)"
              >{{ command }}</el-button
            >
          </div>
          <el-table :data="projectDetail.checks" border>
            <el-table-column prop="itemCode" label="检查编码" />
            <el-table-column prop="itemName" label="检查项" />
            <el-table-column prop="mandatory" label="必选" width="80"
              ><template #default="scope">{{ scope.row.mandatory ? '是' : '否' }}</template></el-table-column
            >
            <el-table-column prop="state" label="状态" width="120" />
            <el-table-column label="操作" width="100">
              <template #default="scope">
                <el-button
                  v-if="scope.row.state === 'PENDING'"
                  v-hasPermi="['service:project:operate']"
                  link
                  type="primary"
                  @click.stop="completeCheck(scope.row)"
                  >完成</el-button
                >
              </template>
            </el-table-column>
          </el-table>
        </template>
      </el-tab-pane>

      <el-tab-pane label="服务工单" name="ticket">
        <el-form :model="ticketForm" label-width="90px" class="form-grid">
          <el-form-item label="服务类型"><el-input v-model="ticketForm.serviceType" /></el-form-item>
          <el-form-item label="优先级"
            ><el-select v-model="ticketForm.priority"><el-option v-for="item in priorities" :key="item" :label="item" :value="item" /></el-select
          ></el-form-item>
          <el-form-item label="主题"><el-input v-model="ticketForm.subject" /></el-form-item>
          <el-form-item label="内部目标"
            ><el-input-number v-model="ticketForm.internalTargetMinutes" :min="1" /><span class="hint">分钟，非合同 SLA</span></el-form-item
          >
          <el-form-item label="说明" class="span-two"><el-input v-model="ticketForm.description" type="textarea" /></el-form-item>
        </el-form>
        <div class="toolbar">
          <el-button v-hasPermi="['service:ticket:create']" type="primary" :loading="loading" @click="createTicket">创建工单</el-button>
          <el-button v-hasPermi="['service:ticket:read']" :loading="loading" @click="refreshTickets">刷新</el-button>
        </div>
        <el-table :data="tickets" border @row-click="openTicket">
          <el-table-column prop="ticketId" label="工单 ULID" min-width="220" />
          <el-table-column prop="priority" label="优先级" width="80" />
          <el-table-column prop="subject" label="主题" />
          <el-table-column prop="state" label="状态" width="130" />
          <el-table-column prop="assigneeUserId" label="责任人" width="100" />
          <el-table-column prop="targetAt" label="内部目标 UTC" width="180" />
        </el-table>
        <template v-if="ticketDetail">
          <el-alert v-if="ticketDetail.overdue" class="mt-3" type="error" :closable="false" title="已超过内部时间目标（不构成商业 SLA）" />
          <div class="toolbar">
            <el-input v-model="actionReason" placeholder="受审计的操作原因" class="reason" />
            <el-input-number v-model="ticketAction.assigneeUserId" :min="1" controls-position="right" />
            <el-input-number v-model="ticketAction.leaseMinutes" :min="1" :max="1440" controls-position="right" />
            <el-button v-for="command in ticketCommands" :key="command" v-hasPermi="['service:ticket:operate']" @click="runTicketCommand(command)">{{
              command
            }}</el-button>
          </div>
          <div class="toolbar">
            <input ref="fileInput" type="file" accept="application/pdf,image/png,image/jpeg,text/plain,text/csv" />
            <el-button v-hasPermi="['service:attachment:upload']" @click="uploadAttachment">上传受控附件</el-button>
          </div>
          <el-table :data="ticketDetail.attachments" border>
            <el-table-column prop="fileName" label="附件" />
            <el-table-column prop="mediaType" label="媒体类型" />
            <el-table-column prop="sha256" label="SHA-256" min-width="260" />
            <el-table-column prop="state" label="状态" width="100" />
            <el-table-column label="操作" width="160">
              <template #default="scope">
                <el-button
                  v-if="scope.row.state === 'STORED'"
                  v-hasPermi="['service:attachment:download']"
                  link
                  @click.stop="downloadAttachment(scope.row)"
                  >短期下载</el-button
                >
                <el-button
                  v-if="scope.row.state === 'STORED'"
                  v-hasPermi="['service:attachment:cleanup']"
                  data-testid="service-attachment-cleanup"
                  link
                  type="danger"
                  @click.stop="cleanupAttachment(scope.row)"
                  >清理正文</el-button
                >
              </template>
            </el-table-column>
          </el-table>
        </template>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ElMessageBox } from 'element-plus';
import OwnerPageFeedback from '@/views/operations/components/OwnerPageFeedback.vue';
import { useRecoverablePage } from '@/composables/useRecoverablePage';
import { useStableOperationIdentity } from '@/composables/useStableOperationIdentity';
import {
  cleanupServiceAttachment,
  commandServiceProject,
  commandServiceTicket,
  completeServiceProjectCheck,
  createServiceCatalog,
  createServiceProject,
  createServiceTicket,
  getServiceProject,
  getServiceTicket,
  issueServiceAttachmentDownload,
  listServiceProjects,
  listServiceTickets,
  publishServiceCatalog,
  uploadServiceAttachment
} from '@/api/service';
import type {
  AttachmentRecord,
  CatalogDetail,
  CheckRecord,
  ProjectDetail,
  ProjectRecord,
  ServiceIdentity,
  TicketDetail,
  TicketRecord
} from '@/api/service/types';
import { newOperationCommandId } from '@/api/operations';

const activeTab = ref('catalog');
const { phase, failure, submitting, runRead, runWrite } = useRecoverablePage('SERVICE_PAGE_FAILED');
const operationKeys = useStableOperationIdentity(newOperationCommandId);
const loading = computed(() => phase.value === 'LOADING' || submitting.value);
const storeId = ref(1001);
const actionReason = ref('已完成权限、影响与审计复核');
const catalogDetail = ref<CatalogDetail>();
const projectDetail = ref<ProjectDetail>();
const ticketDetail = ref<TicketDetail>();
const projects = ref<ProjectRecord[]>([]);
const tickets = ref<TicketRecord[]>([]);
const fileInput = ref<HTMLInputElement>();
const priorities = ['P0', 'P1', 'P2', 'P3'];
const projectCommands = ['PREFLIGHT', 'PREFLIGHT_FAILED', 'MARK_READY', 'START', 'BLOCK', 'UNBLOCK', 'READY_TO_HANDOVER', 'HANDOVER', 'CANCEL'];
const ticketCommands = ['CLAIM', 'ASSIGN', 'START', 'WAIT_FOR_INPUT', 'RESOLVE', 'CLOSE', 'REOPEN', 'CANCEL'];
const catalogForm = reactive({
  catalogCode: 'STANDARD_IMPLEMENTATION',
  versionNo: 1,
  industryTemplate: 'CONVENIENCE',
  name: '标准开店实施目录',
  items: [
    { itemCode: 'STORE_CONFIG', itemName: '门店配置核验', mandatory: true, sequenceNo: 1 },
    { itemCode: 'CATALOG_PRICE', itemName: '商品价格发布核验', mandatory: true, sequenceNo: 2 },
    { itemCode: 'EXTERNAL_P0', itemName: '外部 P0 阻断明确呈现', mandatory: true, sequenceNo: 3 }
  ]
});
const projectForm = reactive({ catalogId: '', targetDate: new Date().toISOString().slice(0, 10) });
const ticketForm = reactive({
  serviceType: 'IMPLEMENTATION_SUPPORT',
  priority: 'P2',
  subject: '内部实施支持',
  description: '不包含真实 PII、Secret 或设备控制',
  internalTargetMinutes: 240
});
const ticketAction = reactive({ assigneeUserId: 1, leaseMinutes: 30 });
const identity = (action: string): ServiceIdentity => {
  const value = operationKeys.get(action);
  return { idempotencyKey: value, correlationId: value };
};
const run = <T,>(work: () => Promise<T>, empty: (value: T) => boolean = () => false): Promise<T | undefined> => runRead(work, empty);
const command = async <T,>(action: string, work: (value: ServiceIdentity) => Promise<T>): Promise<T | undefined> => {
  const result = await runWrite(`service:${action}`, () => work(identity(action)));
  if (result !== undefined) operationKeys.complete(action);
  return result;
};

const confirmImpact = async (title: string, message: string): Promise<boolean> => {
  try {
    await ElMessageBox.confirm(message, title, { type: 'warning', confirmButtonText: '确认执行', cancelButtonText: '取消' });
    return true;
  } catch {
    return false;
  }
};

const createCatalog = async () => {
  const result = await command('catalog-create', (i) => createServiceCatalog({ ...catalogForm }, i));
  if (!result) return;
  catalogDetail.value = result.data;
  projectForm.catalogId = catalogDetail.value.catalog.catalogId;
};
const publishCatalog = async () => {
  if (!catalogDetail.value) return;
  if (!(await confirmImpact('发布服务目录', `目录 ${catalogDetail.value.catalog.catalogId} 将成为不可变版本。`))) return;
  const result = await command('catalog-publish', (i) => publishServiceCatalog(catalogDetail.value!.catalog.catalogId, i));
  if (result) catalogDetail.value = result.data;
};
const refreshProjects = async () => {
  const result = await run(() => listServiceProjects(storeId.value), (value) => value.data.length === 0);
  if (result) projects.value = result.data;
};
const createProject = async () => {
  const result = await command('project-create', (i) => createServiceProject({ storeId: storeId.value, ...projectForm }, i));
  if (!result) return;
  projectDetail.value = result.data;
  await refreshProjects();
};
const openProject = async (row: ProjectRecord) => {
  const result = await run(() => getServiceProject(row.projectId));
  if (result) projectDetail.value = result.data;
};
const runProjectCommand = async (name: string) => {
  if (!projectDetail.value) return;
  const project = projectDetail.value.project;
  if (!(await confirmImpact('实施项目状态确认', `项目 ${project.projectId}；版本 ${project.recordVersion}；动作 ${name}。`))) return;
  const result = await command(`project-${name}-${project.recordVersion}`, (i) =>
    commandServiceProject(project.projectId, project.recordVersion, { command: name, reason: actionReason.value }, i)
  );
  if (!result) return;
  projectDetail.value = result.data;
  await refreshProjects();
};
const completeCheck = async (check: CheckRecord) => {
  if (!projectDetail.value) return;
  const projectId = projectDetail.value.project.projectId;
  const result = await command(`check-${check.checkId}-${check.recordVersion}`, (i) =>
    completeServiceProjectCheck(projectId, check.checkId, check.recordVersion, actionReason.value, i)
  );
  if (result) projectDetail.value = result.data;
};

const refreshTickets = async () => {
  const result = await run(() => listServiceTickets(storeId.value), (value) => value.data.length === 0);
  if (result) tickets.value = result.data;
};
const createTicket = async () => {
  const result = await command('ticket-create', (i) =>
    createServiceTicket({ storeId: storeId.value, projectId: projectDetail.value?.project.projectId, ...ticketForm }, i)
  );
  if (!result) return;
  ticketDetail.value = result.data;
  await refreshTickets();
};
const openTicket = async (row: TicketRecord) => {
  const result = await run(() => getServiceTicket(row.ticketId));
  if (result) ticketDetail.value = result.data;
};
const runTicketCommand = async (name: string) => {
  if (!ticketDetail.value) return;
  const ticket = ticketDetail.value.ticket;
  if (!(await confirmImpact('服务工单状态确认', `工单 ${ticket.ticketId}；版本 ${ticket.recordVersion}；动作 ${name}；租约 ${ticketAction.leaseMinutes} 分钟。`))) return;
  const result = await command(`ticket-${name}-${ticket.recordVersion}`, (i) =>
      commandServiceTicket(
        ticket.ticketId,
        ticket.recordVersion,
        {
          command: name,
          assigneeUserId: ticketAction.assigneeUserId,
          leaseMinutes: ticketAction.leaseMinutes,
          reason: actionReason.value,
          resolutionSummary: name === 'RESOLVE' ? actionReason.value : undefined
        },
        i
      )
    );
  if (!result) return;
  ticketDetail.value = result.data;
  await refreshTickets();
};
const uploadAttachment = async () => {
  const file = fileInput.value?.files?.[0];
  if (!ticketDetail.value || !file) return;
  const ticketId = ticketDetail.value.ticket.ticketId;
  const uploaded = await command(`attachment-upload-${file.name}-${file.size}`, (i) => uploadServiceAttachment(ticketId, file, i));
  if (!uploaded) return;
  const refreshed = await run(() => getServiceTicket(ticketId));
  if (refreshed) ticketDetail.value = refreshed.data;
};
const downloadAttachment = async (attachment: AttachmentRecord) => {
  if (!ticketDetail.value) return;
  const ticketId = ticketDetail.value.ticket.ticketId;
  await runWrite(`service:attachment:${attachment.attachmentId}:download`, async () => {
    const value = (await issueServiceAttachmentDownload(ticketId, attachment.attachmentId)).data;
    const opened = window.open(value.downloadUrl, '_blank', 'noopener,noreferrer');
    if (!opened) throw new Error('SERVICE_ATTACHMENT_DOWNLOAD_BLOCKED');
    return value;
  });
};
const cleanupAttachment = async (attachment: AttachmentRecord) => {
  if (!ticketDetail.value) return;
  const ticketId = ticketDetail.value.ticket.ticketId;
  if (!(await confirmImpact('清理附件正文', `附件 ${attachment.attachmentId}；SHA-256 ${attachment.sha256}。清理后正文不可通过该引用下载。`))) return;
  await command(`attachment-clean-${attachment.attachmentId}`, (i) => cleanupServiceAttachment(ticketId, attachment.attachmentId, i));
  const refreshed = await run(() => getServiceTicket(ticketId));
  if (refreshed) ticketDetail.value = refreshed.data;
};

const retryActive = () => (activeTab.value === 'project' ? refreshProjects() : activeTab.value === 'ticket' ? refreshTickets() : Promise.resolve());
</script>

<style scoped>
.mt-3 {
  margin-top: 12px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(300px, 1fr));
  gap: 0 16px;
}
.span-two {
  grid-column: span 2;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin: 12px 0;
}
.reason,
.ulid {
  width: 360px;
}
.hint {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
}
</style>
