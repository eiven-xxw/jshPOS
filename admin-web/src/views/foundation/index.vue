<template>
  <div class="p-2">
    <el-alert
      class="mb-3"
      title="组织、门店与员工数据范围"
      description="租户由可信登录会话注入；页面不会接收、保存或提交 tenant_id，服务端仍执行最终权限与数据范围校验。"
      type="info"
      :closable="false"
      show-icon
    />
    <el-alert
      v-if="pageFailure"
      data-testid="foundation-error"
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
        <el-button data-testid="foundation-retry" type="primary" link @click="loadAll">刷新权威状态</el-button>
      </template>
    </el-alert>
    <el-alert
      v-else-if="pagePhase === 'EMPTY'"
      data-testid="foundation-empty"
      class="mb-3"
      type="info"
      :closable="false"
      title="当前数据范围内暂无组织、门店、模板或审计记录"
    />

    <el-row :gutter="12" class="mb-3">
      <el-col :xs="12" :sm="6"
        ><el-card shadow="never"><el-statistic title="可见组织" :value="orgUnits.length" /></el-card
      ></el-col>
      <el-col :xs="12" :sm="6"
        ><el-card shadow="never"><el-statistic title="可见门店" :value="stores.length" /></el-card
      ></el-col>
      <el-col :xs="12" :sm="6"
        ><el-card shadow="never"><el-statistic title="配置模板" :value="templates.length" /></el-card
      ></el-col>
      <el-col :xs="12" :sm="6"
        ><el-card shadow="never"><el-statistic title="最近审计" :value="auditEvents.length" /></el-card
      ></el-col>
    </el-row>

    <el-card shadow="hover">
      <template #header>
        <el-space>
          <el-button v-hasPermi="['foundation:org:query']" data-testid="foundation-refresh" icon="Refresh" :disabled="submitting" @click="loadAll"
            >刷新</el-button
          >
          <el-button v-hasPermi="['foundation:org:manage']" type="primary" icon="Plus" @click="openOrgDialog">新增组织</el-button>
          <el-button v-hasPermi="['foundation:store:manage']" type="success" icon="Plus" @click="openStoreDialog">新增门店</el-button>
          <el-button v-hasPermi="['foundation:config:manage']" type="warning" icon="Plus" @click="openTemplateDialog">新增模板</el-button>
        </el-space>
      </template>

      <el-tabs v-model="activeTab" v-loading="loading">
        <el-tab-pane label="业务组织" name="org">
          <el-table :data="orgUnits" row-key="orgUnitId" border>
            <el-table-column prop="code" label="编码" min-width="120" />
            <el-table-column prop="name" label="名称" min-width="160" />
            <el-table-column prop="type" label="类型" min-width="130" />
            <el-table-column prop="parentId" label="上级 ID" min-width="100" />
            <el-table-column prop="treeDepth" label="层级" width="80" />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="scope"
                ><el-tag :type="scope.row.status === 'ACTIVE' ? 'success' : 'info'">{{ scope.row.status }}</el-tag></template
              >
            </el-table-column>
            <el-table-column prop="version" label="版本" width="80" />
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="门店与业务日" name="store">
          <el-table :data="stores" row-key="storeId" border>
            <el-table-column prop="code" label="编码" min-width="110" />
            <el-table-column prop="name" label="名称" min-width="160" />
            <el-table-column prop="orgUnitId" label="组织 ID" width="100" />
            <el-table-column prop="zoneId" label="业务时区" min-width="140" />
            <el-table-column prop="businessDayStart" label="营业日起点" width="120" />
            <el-table-column prop="status" label="状态" width="110" />
            <el-table-column label="当前业务日" min-width="160">
              <template #default="scope">
                <el-button v-hasPermi="['foundation:store:query']" link type="primary" :disabled="submitting" @click="showBusinessDate(scope.row)"
                  >查询</el-button
                >
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="员工数据范围" name="staff">
          <el-alert
            class="mb-3"
            title="员工账号与角色在“系统管理 → 用户/角色”维护；此处只授予鲸熵汇组织门店数据范围。"
            type="info"
            :closable="false"
            show-icon
          />
          <el-form inline>
            <el-form-item label="员工用户 ID"><el-input-number v-model="staffUserId" :min="1" :precision="0" /></el-form-item>
            <el-form-item>
              <el-button v-hasPermi="['foundation:scope:query']" type="primary" @click="loadStaffScopes">加载范围</el-button>
              <el-button v-hasPermi="['foundation:scope:grant']" @click="addScopeRow">新增范围</el-button>
              <el-button v-hasPermi="['foundation:scope:grant']" type="success" @click="saveStaffScopes">保存并审计</el-button>
            </el-form-item>
          </el-form>
          <el-table :data="staffScopes" row-key="rowKey" border empty-text="请先输入员工用户 ID 并加载">
            <el-table-column label="范围类型" min-width="170">
              <template #default="scope">
                <el-select v-model="scope.row.scopeType" class="w-full">
                  <el-option label="全租户" value="TENANT" />
                  <el-option label="组织及下级" value="ORG_SUBTREE" />
                  <el-option label="指定门店" value="STORE" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="组织" min-width="200">
              <template #default="scope">
                <el-select v-model="scope.row.orgUnitId" :disabled="scope.row.scopeType !== 'ORG_SUBTREE'" clearable class="w-full">
                  <el-option v-for="item in orgUnits" :key="item.orgUnitId" :label="`${item.code} - ${item.name}`" :value="item.orgUnitId" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="门店" min-width="200">
              <template #default="scope">
                <el-select v-model="scope.row.storeId" :disabled="scope.row.scopeType !== 'STORE'" clearable class="w-full">
                  <el-option v-for="item in stores" :key="item.storeId" :label="`${item.code} - ${item.name}`" :value="item.storeId" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90">
              <template #default="scope"
                ><el-button
                  v-hasPermi="['foundation:scope:grant']"
                  link
                  type="danger"
                  :disabled="submitting"
                  @click="staffScopes.splice(scope.$index, 1)"
                  >移除</el-button
                ></template
              >
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="行业配置" name="config">
          <el-table :data="templates" row-key="templateId" border>
            <el-table-column prop="code" label="编码" min-width="120" />
            <el-table-column prop="name" label="名称" min-width="160" />
            <el-table-column prop="industry" label="业态" min-width="180" />
            <el-table-column prop="status" label="状态" width="100" />
            <el-table-column prop="version" label="乐观锁版本" width="120" />
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="领域审计" name="audit">
          <el-table :data="auditEvents" row-key="auditId" border>
            <el-table-column prop="occurredAt" label="发生时间" min-width="190" />
            <el-table-column prop="actionCode" label="动作" min-width="220" />
            <el-table-column prop="targetType" label="对象类型" min-width="150" />
            <el-table-column prop="targetId" label="对象 ID" min-width="120" />
            <el-table-column prop="correlationId" label="关联标识" min-width="220" show-overflow-tooltip />
            <el-table-column prop="result" label="结果" width="100" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="orgDialog" title="新增业务组织" width="520px" destroy-on-close>
      <el-form :model="orgForm" label-width="110px">
        <el-form-item label="上级组织"
          ><el-select v-model="orgForm.parentId" clearable class="w-full"
            ><el-option v-for="item in orgUnits" :key="item.orgUnitId" :label="`${item.code} - ${item.name}`" :value="item.orgUnitId" /></el-select
        ></el-form-item>
        <el-form-item label="组织编码"><el-input v-model="orgForm.code" maxlength="32" /></el-form-item>
        <el-form-item label="组织名称"><el-input v-model="orgForm.name" maxlength="100" /></el-form-item>
        <el-form-item label="组织类型"
          ><el-select v-model="orgForm.type" class="w-full"><el-option v-for="item in orgTypes" :key="item" :label="item" :value="item" /></el-select
        ></el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="orgDialog = false">取消</el-button
        ><el-button v-hasPermi="['foundation:org:manage']" type="primary" :loading="submitting" @click="submitOrg">保存</el-button></template
      >
    </el-dialog>

    <el-dialog v-model="storeDialog" title="新增门店" width="560px" destroy-on-close>
      <el-form :model="storeForm" label-width="110px">
        <el-form-item label="所属组织"
          ><el-select v-model="storeForm.orgUnitId" class="w-full"
            ><el-option v-for="item in orgUnits" :key="item.orgUnitId" :label="`${item.code} - ${item.name}`" :value="item.orgUnitId" /></el-select
        ></el-form-item>
        <el-form-item label="门店编码"><el-input v-model="storeForm.code" maxlength="32" /></el-form-item>
        <el-form-item label="门店名称"><el-input v-model="storeForm.name" maxlength="100" /></el-form-item>
        <el-form-item label="业务时区"><el-input v-model="storeForm.zoneId" /></el-form-item>
        <el-form-item label="营业日起点"><el-time-picker v-model="storeStart" format="HH:mm" value-format="HH:mm:ss" class="w-full" /></el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="storeDialog = false">取消</el-button
        ><el-button v-hasPermi="['foundation:store:manage']" type="primary" :loading="submitting" @click="submitStore">保存</el-button></template
      >
    </el-dialog>

    <el-dialog v-model="templateDialog" title="新增行业配置模板" width="520px" destroy-on-close>
      <el-form :model="templateForm" label-width="110px">
        <el-form-item label="模板编码"><el-input v-model="templateForm.code" maxlength="32" /></el-form-item>
        <el-form-item label="模板名称"><el-input v-model="templateForm.name" maxlength="100" /></el-form-item>
        <el-form-item label="首发业态"
          ><el-select v-model="templateForm.industry" class="w-full"
            ><el-option v-for="item in industries" :key="item" :label="item" :value="item" /></el-select
        ></el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="templateDialog = false">取消</el-button
        ><el-button v-hasPermi="['foundation:config:manage']" type="primary" :loading="submitting" @click="submitTemplate">保存</el-button></template
      >
    </el-dialog>
  </div>
</template>

<script setup name="FoundationWorkbench" lang="ts">
import {
  createConfigTemplate,
  createOrgUnit,
  createStore,
  getBusinessDate,
  listAuditEvents,
  listConfigTemplates,
  listOrgUnits,
  listStaffScopes,
  listStores,
  replaceStaffScopes
} from '@/api/foundation';
import type {
  AuditEventVO,
  ConfigTemplateVO,
  CreateConfigTemplateForm,
  CreateOrgUnitForm,
  CreateStoreForm,
  Industry,
  OrgUnitType,
  OrgUnitVO,
  ScopeType,
  StaffScopeInput,
  StoreVO
} from '@/api/foundation/types';
import { useRecoverablePage } from '@/composables/useRecoverablePage';

const loading = ref(false);
const { phase: pagePhase, failure: pageFailure, submitting, runRead, runWrite } = useRecoverablePage('FOUNDATION_OPERATION_FAILED');
const activeTab = ref('org');
const orgUnits = ref<OrgUnitVO[]>([]);
const stores = ref<StoreVO[]>([]);
const templates = ref<ConfigTemplateVO[]>([]);
const auditEvents = ref<AuditEventVO[]>([]);
const staffUserId = ref(1);
const staffScopes = ref<Array<StaffScopeInput & { rowKey: string }>>([]);
const orgDialog = ref(false);
const storeDialog = ref(false);
const templateDialog = ref(false);
const storeStart = ref('06:00:00');
const orgTypes: OrgUnitType[] = ['HEADQUARTERS', 'REGION', 'COMPANY', 'OTHER'];
const industries: Industry[] = ['CONVENIENCE', 'SNACK_DISCOUNT', 'COMMUNITY_SUPERMARKET'];
let scopeSequence = 0;

const orgForm = reactive<CreateOrgUnitForm>({ code: '', name: '', type: 'COMPANY' });
const storeForm = reactive<CreateStoreForm>({ orgUnitId: 0, code: '', name: '', zoneId: 'Asia/Shanghai', businessDayStart: '06:00:00' });
const templateForm = reactive<CreateConfigTemplateForm>({ code: '', name: '', industry: 'CONVENIENCE' });

const loadAll = async () => {
  loading.value = true;
  try {
    await runRead(
      async () => {
        const [orgResponse, storeResponse, templateResponse, auditResponse] = await Promise.all([
          listOrgUnits(),
          listStores(),
          listConfigTemplates(),
          listAuditEvents(undefined, 100)
        ]);
        orgUnits.value = orgResponse.data;
        stores.value = storeResponse.data;
        templates.value = templateResponse.data;
        auditEvents.value = auditResponse.data;
        return orgUnits.value.length + stores.value.length + templates.value.length + auditEvents.value.length;
      },
      (count) => count === 0
    );
  } finally {
    loading.value = false;
  }
};

const openOrgDialog = () => {
  Object.assign(orgForm, { parentId: undefined, code: '', name: '', type: 'COMPANY' });
  orgDialog.value = true;
};
const openStoreDialog = () => {
  Object.assign(storeForm, {
    orgUnitId: orgUnits.value[0]?.orgUnitId ?? 0,
    code: '',
    name: '',
    zoneId: 'Asia/Shanghai',
    businessDayStart: '06:00:00'
  });
  storeStart.value = '06:00:00';
  storeDialog.value = true;
};
const openTemplateDialog = () => {
  Object.assign(templateForm, { code: '', name: '', industry: 'CONVENIENCE' });
  templateDialog.value = true;
};

const submitOrg = async () => {
  const response = await runWrite(`foundation:org:${orgForm.code}`, () => createOrgUnit({ ...orgForm }));
  if (!response) return;
  orgDialog.value = false;
  ElMessage.success('组织已创建');
  await loadAll();
};
const submitStore = async () => {
  storeForm.businessDayStart = storeStart.value;
  const response = await runWrite(`foundation:store:${storeForm.code}`, () => createStore({ ...storeForm }));
  if (!response) return;
  storeDialog.value = false;
  ElMessage.success('门店已创建');
  await loadAll();
};
const submitTemplate = async () => {
  const response = await runWrite(`foundation:template:${templateForm.code}`, () => createConfigTemplate({ ...templateForm }));
  if (!response) return;
  templateDialog.value = false;
  ElMessage.success('模板已创建');
  await loadAll();
};
const showBusinessDate = async (store: StoreVO) => {
  const response = await runRead(() => getBusinessDate(store.storeId));
  if (!response) return;
  ElMessage.success(`${store.name} 当前业务日：${response.data.businessDate}`);
};

const addScopeRow = () => {
  scopeSequence += 1;
  staffScopes.value.push({ rowKey: `scope-${scopeSequence}`, scopeType: 'STORE', storeId: stores.value[0]?.storeId });
};
const loadStaffScopes = async () => {
  const response = await runRead(() => listStaffScopes(staffUserId.value));
  if (!response) return;
  staffScopes.value = response.data
    .filter((item) => item.status === 'ACTIVE')
    .map((item) => ({
      rowKey: `scope-${item.staffScopeId}`,
      scopeType: item.scopeType,
      orgUnitId: item.orgUnitId,
      storeId: item.storeId
    }));
};
const saveStaffScopes = async () => {
  const scopes = staffScopes.value.map<StaffScopeInput>((item) => {
    const scopeType: ScopeType = item.scopeType;
    if (scopeType === 'ORG_SUBTREE' && !item.orgUnitId) throw new Error('组织范围必须选择组织');
    if (scopeType === 'STORE' && !item.storeId) throw new Error('门店范围必须选择门店');
    return {
      scopeType,
      orgUnitId: scopeType === 'ORG_SUBTREE' ? item.orgUnitId : undefined,
      storeId: scopeType === 'STORE' ? item.storeId : undefined
    };
  });
  const response = await runWrite(`foundation:scope:${staffUserId.value}`, () => replaceStaffScopes(staffUserId.value, scopes));
  if (!response) return;
  ElMessage.success('员工数据范围已替换并记录审计');
  await loadStaffScopes();
};

onMounted(loadAll);
</script>
