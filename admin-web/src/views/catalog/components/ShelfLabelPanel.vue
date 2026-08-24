<template>
  <el-drawer :model-value="modelValue" title="货架价签工作台" size="92%" destroy-on-close @update:model-value="emit('update:modelValue', $event)">
    <el-alert
      class="mb-3"
      type="warning"
      :closable="false"
      show-icon
      title="真实打印尚未解阻"
      description="本页只提供价签任务、纯文本预览、人工换签确认和失败关闭打印入口；任何软件状态均不代表打印机成功。"
    />
    <el-alert
      v-if="pageFailure"
      data-testid="shelf-label-error"
      class="mb-3"
      type="error"
      :closable="false"
      show-icon
      :title="`${pageFailure.message}（${pageFailure.code}）`"
      :description="`关联标识：${pageFailure.correlationId}${pageFailure.operationIdentity ? `；原操作：${pageFailure.operationIdentity}` : ''}`"
    >
      <template #default>
        <span
          >关联标识：{{ pageFailure.correlationId
          }}<template v-if="pageFailure.operationIdentity">；原操作：{{ pageFailure.operationIdentity }}</template></span
        >
        <el-button data-testid="shelf-label-retry" type="primary" link @click="refresh">刷新原任务状态</el-button>
      </template>
    </el-alert>
    <el-alert
      v-else-if="pagePhase === 'EMPTY'"
      data-testid="shelf-label-empty"
      class="mb-3"
      type="info"
      :closable="false"
      title="当前筛选范围内暂无价签任务或模板"
    />

    <el-tabs v-model="activeTab" @tab-change="refresh">
      <el-tab-pane label="换签任务" name="tasks">
        <el-space class="mb-3" wrap>
          <el-select v-model="query.storeId" clearable placeholder="全部可访问门店" style="width: 240px">
            <el-option v-for="store in stores" :key="store.storeId" :label="`${store.code} - ${store.name}`" :value="store.storeId" />
          </el-select>
          <el-select v-model="query.state" clearable placeholder="全部状态" style="width: 190px">
            <el-option v-for="state in taskStates" :key="state" :label="taskStateLabel(state)" :value="state" />
          </el-select>
          <el-button v-hasPermi="['catalog:label:task:read']" icon="Refresh" :loading="loading" :disabled="submitting" @click="loadTasks"
            >刷新</el-button
          >
        </el-space>
        <el-table :data="tasks" border row-key="taskId" @row-click="openTask">
          <el-table-column prop="taskId" label="任务 ID" min-width="170" />
          <el-table-column prop="storeName" label="门店" min-width="160" />
          <el-table-column prop="sourcePriceVersion" label="价格版本" width="100" />
          <el-table-column prop="effectiveAt" label="生效时间" min-width="180" />
          <el-table-column label="进度" width="150">
            <template #default="scope">{{ scope.row.itemCount - scope.row.pendingCount }}/{{ scope.row.itemCount }}</template>
          </el-table-column>
          <el-table-column label="状态" width="150">
            <template #default="scope"
              ><el-tag :type="taskTagType(scope.row.state)">{{ taskStateLabel(scope.row.state) }}</el-tag></template
            >
          </el-table-column>
          <el-table-column prop="exceptionCount" label="异常" width="80" />
          <el-table-column label="操作" width="90"
            ><template #default="scope"
              ><el-button v-hasPermi="['catalog:label:task:read']" link :disabled="submitting" @click.stop="openTask(scope.row)"
                >详情</el-button
              ></template
            ></el-table-column
          >
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="模板版本" name="templates">
        <el-space class="mb-3" wrap>
          <el-button v-hasPermi="['catalog:label:template:manage']" type="primary" @click="templateDialog = true">新建模板草稿</el-button>
          <el-button v-hasPermi="['catalog:label:task:read']" icon="Refresh" :loading="loading" :disabled="submitting" @click="loadTemplates"
            >刷新</el-button
          >
        </el-space>
        <el-table :data="templates" border row-key="templateId">
          <el-table-column prop="templateCode" label="编码" min-width="150" />
          <el-table-column prop="templateName" label="名称" min-width="180" />
          <el-table-column prop="versionNo" label="业务版本" width="100" />
          <el-table-column prop="scopeType" label="范围" width="90" />
          <el-table-column prop="storeId" label="门店 ID" min-width="150" />
          <el-table-column prop="state" label="状态" width="110" />
          <el-table-column label="摘要" min-width="150"
            ><template #default="scope">{{ shortHash(scope.row.contentSha256) }}</template></el-table-column
          >
          <el-table-column label="操作" width="190">
            <template #default="scope">
              <el-button
                v-if="scope.row.state === 'DRAFT'"
                v-hasPermi="['catalog:label:template:publish']"
                link
                type="success"
                @click="publishTemplate(scope.row)"
                >发布</el-button
              >
              <el-button
                v-if="scope.row.state === 'PUBLISHED'"
                v-hasPermi="['catalog:label:template:publish']"
                link
                type="warning"
                @click="retireTemplate(scope.row)"
                >停用</el-button
              >
              <el-popover placement="left" :width="420" trigger="click">
                <template #reference><el-button link>查看纯文本</el-button></template>
                <pre class="label-preview">{{ scope.row.bodyTemplate }}</pre>
              </el-popover>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="templateDialog" title="新建版本化价签模板" width="720px" append-to-body>
      <el-form :model="templateForm" label-width="110px">
        <el-form-item label="模板编码"><el-input v-model="templateForm.templateCode" maxlength="64" /></el-form-item>
        <el-form-item label="模板名称"><el-input v-model="templateForm.templateName" maxlength="200" /></el-form-item>
        <el-form-item label="业务版本"><el-input-number v-model="templateForm.versionNo" :min="1" :precision="0" /></el-form-item>
        <el-form-item label="作用范围">
          <el-radio-group v-model="templateForm.scopeType"
            ><el-radio value="TENANT">租户</el-radio><el-radio value="STORE">门店</el-radio></el-radio-group
          >
        </el-form-item>
        <el-form-item v-if="templateForm.scopeType === 'STORE'" label="适用门店">
          <el-select v-model="templateForm.storeId" class="w-full">
            <el-option v-for="store in stores" :key="store.storeId" :label="`${store.code} - ${store.name}`" :value="store.storeId" />
          </el-select>
        </el-form-item>
        <el-form-item label="纯文本模板">
          <el-input v-model="templateForm.bodyTemplate" type="textarea" :rows="9" maxlength="2000" show-word-limit />
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false"
        >批准字段示例：productName、skuCode、barcode、unitName、oldPrice、newPrice、storeName、priceVersion、effectiveAt、taskStatus、exceptionReason。</el-alert
      >
      <template #footer
        ><el-button @click="templateDialog = false">取消</el-button
        ><el-button
          v-hasPermi="['catalog:label:template:manage']"
          data-testid="shelf-label-save-template"
          type="primary"
          :loading="submitting"
          @click="saveTemplate"
          >保存草稿</el-button
        ></template
      >
    </el-dialog>

    <el-dialog v-model="taskDialog" title="价签任务详情" width="94%" append-to-body>
      <el-descriptions v-if="taskDetail" :column="4" border class="mb-3">
        <el-descriptions-item label="任务">{{ taskDetail.task.taskId }}</el-descriptions-item>
        <el-descriptions-item label="门店">{{ taskDetail.task.storeName }}</el-descriptions-item>
        <el-descriptions-item label="价格版本">{{ taskDetail.task.sourcePriceVersion }}</el-descriptions-item>
        <el-descriptions-item label="任务状态">{{ taskStateLabel(taskDetail.task.state) }}</el-descriptions-item>
      </el-descriptions>
      <el-table v-if="taskDetail" :data="taskDetail.items" border row-key="itemId" max-height="480">
        <el-table-column prop="skuCode" label="SKU" width="130" />
        <el-table-column prop="productName" label="商品" min-width="170" />
        <el-table-column prop="barcode" label="条码" min-width="150" />
        <el-table-column prop="unitName" label="单位" width="80" />
        <el-table-column label="原价/现价" width="150"
          ><template #default="scope">{{ money(scope.row.oldPriceMinor) }} / {{ money(scope.row.newPriceMinor) }}</template></el-table-column
        >
        <el-table-column prop="effectiveAt" label="生效时间" min-width="170" />
        <el-table-column prop="state" label="状态" width="150" />
        <el-table-column prop="exceptionReason" label="异常" min-width="180" show-overflow-tooltip />
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="scope">
            <el-button v-hasPermi="['catalog:label:task:read']" link type="primary" :disabled="submitting" @click="previewItem(scope.row)"
              >预览</el-button
            >
            <el-button
              v-if="['PENDING', 'PREVIEW_READY', 'EXCEPTION'].includes(scope.row.state)"
              v-hasPermi="['catalog:label:task:confirm']"
              link
              type="success"
              @click="confirmItem(scope.row)"
              >确认换签</el-button
            >
            <el-button
              v-if="!['REPLACED_CONFIRMED', 'SUPERSEDED'].includes(scope.row.state)"
              v-hasPermi="['catalog:label:task:exception']"
              link
              type="danger"
              @click="exceptionItem(scope.row)"
              >报异常</el-button
            >
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="taskDialog = false">关闭</el-button>
        <el-button
          v-hasPermi="['catalog:label:task:dispatch']"
          type="danger"
          :loading="submitting"
          :disabled="!lastPreview || submitting"
          @click="dispatchBlocked"
          >验证打印失败关闭</el-button
        >
      </template>
    </el-dialog>

    <el-dialog v-model="previewDialog" title="安全纯文本预览" width="620px" append-to-body>
      <el-alert type="info" :closable="false" title="以下内容以纯文本渲染，不执行 HTML、脚本、路径或公式。" />
      <pre class="label-preview mt-3">{{ lastPreview?.renderedText }}</pre>
      <el-descriptions v-if="lastPreview" :column="2" border class="mt-3">
        <el-descriptions-item label="模板版本">{{ lastPreview.templateVersion }}</el-descriptions-item>
        <el-descriptions-item label="预览摘要">{{ shortHash(lastPreview.previewSha256) }}</el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </el-drawer>
</template>

<script setup lang="ts">
import {
  confirmShelfLabelReplacement,
  createShelfLabelTemplate,
  dispatchShelfLabelTask,
  getShelfLabelTask,
  listShelfLabelTasks,
  listShelfLabelTemplates,
  previewShelfLabelItem,
  publishShelfLabelTemplate,
  recordShelfLabelException,
  retireShelfLabelTemplate
} from '@/api/catalog';
import { shelfLabelCommandIdentity } from '@/api/catalog/contract';
import type {
  ShelfLabelPreviewVO,
  ShelfLabelTaskDetailVO,
  ShelfLabelTaskItemVO,
  ShelfLabelTaskState,
  ShelfLabelTaskVO,
  ShelfLabelTemplateVO
} from '@/api/catalog/types';
import type { StoreVO } from '@/api/foundation/types';
import { useRecoverablePage } from '@/composables/useRecoverablePage';

const props = defineProps<{ modelValue: boolean; stores: StoreVO[] }>();
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>();
const activeTab = ref('tasks');
const loading = ref(false);
const { phase: pagePhase, failure: pageFailure, submitting, runRead, runWrite } = useRecoverablePage('SHELF_LABEL_OPERATION_FAILED');
const tasks = ref<ShelfLabelTaskVO[]>([]);
const templates = ref<ShelfLabelTemplateVO[]>([]);
const taskDetail = ref<ShelfLabelTaskDetailVO>();
const lastPreview = ref<ShelfLabelPreviewVO>();
const templateDialog = ref(false);
const taskDialog = ref(false);
const previewDialog = ref(false);
const query = reactive<{ storeId?: string | number; state?: ShelfLabelTaskState }>({});
const commandIdentities = new Map<string, ReturnType<typeof shelfLabelCommandIdentity>>();
const taskStates: ShelfLabelTaskState[] = ['PENDING', 'PREVIEW_READY', 'IN_PROGRESS', 'COMPLETED', 'EXCEPTION', 'SUPERSEDED', 'DISPATCH_BLOCKED'];
const templateForm = reactive({
  templateCode: '',
  templateName: '',
  versionNo: 1,
  scopeType: 'TENANT' as 'TENANT' | 'STORE',
  storeId: undefined as string | number | undefined,
  bodyTemplate: '{{productName}}\n{{barcode}} {{unitName}}\n原价 {{oldPrice}} 现价 {{newPrice}}\n{{storeName}} V{{priceVersion}} {{effectiveAt}}'
});

const identityFor = (key: string) => {
  const existing = commandIdentities.get(key);
  if (existing) return existing;
  const created = shelfLabelCommandIdentity(key);
  commandIdentities.set(key, created);
  return created;
};
const done = (key: string) => commandIdentities.delete(key);
const loadTasks = async () => {
  loading.value = true;
  try {
    const response = await runRead(
      () => listShelfLabelTasks(query.storeId, query.state),
      (value) => value.data.length === 0
    );
    if (response) tasks.value = response.data;
  } finally {
    loading.value = false;
  }
};
const loadTemplates = async () => {
  loading.value = true;
  try {
    const response = await runRead(
      () => listShelfLabelTemplates(),
      (value) => value.data.length === 0
    );
    if (response) templates.value = response.data;
  } finally {
    loading.value = false;
  }
};
const refresh = () => (activeTab.value === 'tasks' ? loadTasks() : loadTemplates());
const saveTemplate = async () => {
  const key = `template-create:${templateForm.templateCode}:${templateForm.versionNo}`;
  const response = await runWrite(key, () =>
    createShelfLabelTemplate({
      ...templateForm,
      storeId: templateForm.scopeType === 'STORE' ? templateForm.storeId : undefined,
      ...identityFor(key)
    })
  );
  if (!response) return;
  done(key);
  templateDialog.value = false;
  ElMessage.success('价签模板草稿已创建');
  await loadTemplates();
};
const publishTemplate = async (template: ShelfLabelTemplateVO) => {
  await ElMessageBox.confirm(`发布后模板 V${template.versionNo} 内容不可修改，是否继续？`, '发布价签模板', { type: 'warning' });
  const key = `template-publish:${template.templateId}:${template.version}`;
  const response = await runWrite(key, () => publishShelfLabelTemplate(template.templateId, template.version, identityFor(key)));
  if (!response) return;
  done(key);
  await loadTemplates();
};
const retireTemplate = async (template: ShelfLabelTemplateVO) => {
  await ElMessageBox.confirm('停用仅影响后续默认选取，不会改写历史任务预览。', '停用价签模板', { type: 'warning' });
  const key = `template-retire:${template.templateId}:${template.version}`;
  const response = await runWrite(key, () => retireShelfLabelTemplate(template.templateId, template.version, identityFor(key)));
  if (!response) return;
  done(key);
  await loadTemplates();
};
const openTask = async (task: ShelfLabelTaskVO) => {
  const response = await runRead(() => getShelfLabelTask(task.taskId));
  if (!response) return;
  taskDetail.value = response.data;
  lastPreview.value = undefined;
  taskDialog.value = true;
};
const reloadTask = async () => {
  if (!taskDetail.value) return;
  const response = await runRead(() => getShelfLabelTask(taskDetail.value!.task.taskId));
  if (!response) return;
  taskDetail.value = response.data;
  await loadTasks();
};
const previewItem = async (item: ShelfLabelTaskItemVO) => {
  const key = `preview:${item.itemId}:${item.version}`;
  const response = await runWrite(key, () => previewShelfLabelItem(item.itemId, undefined, identityFor(key)));
  if (!response) return;
  lastPreview.value = response.data;
  done(key);
  previewDialog.value = true;
  await reloadTask();
};
const confirmItem = async (item: ShelfLabelTaskItemVO) => {
  const { value } = await ElMessageBox.prompt('请输入人工换签确认原因（不代表打印成功）', '确认换签', {
    inputPattern: /\S+/,
    inputErrorMessage: '原因不能为空'
  });
  const key = `confirm:${item.itemId}:${item.version}`;
  const response = await runWrite(key, () => confirmShelfLabelReplacement(item.itemId, item.version, value, identityFor(key)));
  if (!response) return;
  done(key);
  await reloadTask();
};
const exceptionItem = async (item: ShelfLabelTaskItemVO) => {
  const { value } = await ElMessageBox.prompt('请填写价签异常原因', '记录异常', { inputPattern: /\S+/, inputErrorMessage: '原因不能为空' });
  const key = `exception:${item.itemId}:${item.version}`;
  const response = await runWrite(key, () => recordShelfLabelException(item.itemId, item.version, value, identityFor(key)));
  if (!response) return;
  done(key);
  await reloadTask();
};
const dispatchBlocked = async () => {
  if (!taskDetail.value || !lastPreview.value) return;
  await ElMessageBox.confirm('该操作只验证未解阻打印端口会失败关闭，并记录 BLOCKED_EXTERNAL。', '打印边界验证', { type: 'warning' });
  const task = taskDetail.value.task;
  const key = `dispatch:${task.taskId}:${task.version}:${lastPreview.value.previewSha256}`;
  const response = await runWrite(key, () => dispatchShelfLabelTask(task.taskId, task.version, lastPreview.value!.previewSha256, identityFor(key)));
  if (!response) return;
  done(key);
  ElMessage.warning('打印端口已按预期失败关闭，未形成真实打印成功证据');
  await reloadTask();
};
const money = (minor?: number) => (minor == null ? '首次/无有效价' : `¥${(minor / 100).toFixed(2)}`);
const shortHash = (hash?: string) => (hash ? `${hash.slice(0, 12)}…` : '-');
const taskStateLabel = (state: ShelfLabelTaskState) =>
  ({
    PENDING: '待预览',
    PREVIEW_READY: '待换签',
    IN_PROGRESS: '换签中',
    COMPLETED: '人工确认完成',
    EXCEPTION: '异常',
    SUPERSEDED: '已被新价格替代',
    DISPATCH_BLOCKED: '真实打印已阻断'
  })[state];
const taskTagType = (state: ShelfLabelTaskState) =>
  state === 'COMPLETED' ? 'success' : state === 'EXCEPTION' || state === 'DISPATCH_BLOCKED' ? 'danger' : state === 'SUPERSEDED' ? 'info' : 'warning';

watch(
  () => props.modelValue,
  (opened) => opened && refresh(),
  { immediate: true }
);
</script>

<style scoped>
.label-preview {
  margin: 0;
  padding: 16px;
  max-height: 420px;
  overflow: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  color: var(--el-text-color-primary);
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
}
</style>
