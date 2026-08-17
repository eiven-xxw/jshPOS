<template>
  <div class="p-2">
    <el-alert
      class="mb-3"
      type="warning"
      :closable="false"
      show-icon
      title="Gate 6A 终端登记"
      description="激活秘密和设备凭据仅显示一次；当前只具备 SYNTHETIC 软件证据，真实设备验收仍为 BLOCKED。"
    />
    <el-card shadow="hover">
      <el-form :inline="true">
        <el-form-item label="门店 ID"><el-input-number v-model="queryStoreId" :min="1" :controls="false" clearable /></el-form-item>
        <el-form-item>
          <el-button v-hasPermi="['terminal:registry:read']" type="primary" icon="Search" :loading="loading" @click="load">查询</el-button>
          <el-button v-hasPermi="['terminal:activation:issue']" type="success" icon="Plus" @click="openIssue">签发激活</el-button>
        </el-form-item>
      </el-form>
      <el-table v-loading="loading" :data="page.items" border>
        <el-table-column prop="deviceId" label="设备 ID" min-width="230" />
        <el-table-column prop="storeId" label="门店" width="90" />
        <el-table-column prop="terminalProfileCode" label="终端模板" width="160" />
        <el-table-column prop="appVersion" label="应用版本" width="100" />
        <el-table-column prop="schemaVersion" label="Schema" width="90" />
        <el-table-column label="状态" width="105">
          <template #default="scope"
            ><el-tag :type="statusType(scope.row.status)">{{ scope.row.status }}</el-tag></template
          >
        </el-table-column>
        <el-table-column label="证据" width="135">
          <template #default="scope"
            ><el-tag :type="scope.row.evidenceLevel === 'REAL_DEVICE' ? 'success' : 'warning'">{{ scope.row.evidenceLevel }}</el-tag></template
          >
        </el-table-column>
        <el-table-column prop="lastSeenAt" label="最后在线" width="180" />
        <el-table-column label="操作" width="245" fixed="right">
          <template #default="scope">
            <el-button
              v-if="scope.row.status === 'ACTIVE'"
              v-hasPermi="['terminal:status:manage']"
              link
              type="warning"
              @click="change(scope.row, 'BLOCKED')"
              >阻断</el-button
            >
            <el-button
              v-if="scope.row.status === 'BLOCKED'"
              v-hasPermi="['terminal:status:manage']"
              link
              type="success"
              @click="change(scope.row, 'ACTIVE')"
              >解阻</el-button
            >
            <el-button
              v-if="['ACTIVE', 'BLOCKED'].includes(scope.row.status)"
              v-hasPermi="['terminal:status:manage']"
              link
              type="danger"
              @click="change(scope.row, 'REVOKED')"
              >吊销</el-button
            >
            <el-button
              v-if="scope.row.status === 'REVOKED'"
              v-hasPermi="['terminal:status:manage']"
              link
              type="danger"
              @click="change(scope.row, 'RETIRED')"
              >退役</el-button
            >
            <el-button v-if="scope.row.status === 'ACTIVE'" v-hasPermi="['terminal:credential:rotate']" link type="primary" @click="rotate(scope.row)"
              >轮换凭据</el-button
            >
          </template>
        </el-table-column>
      </el-table>
      <pagination v-show="page.total > 0" v-model:page="page.page" v-model:limit="page.size" :total="page.total" @pagination="load" />
    </el-card>

    <el-dialog v-model="issueDialog" title="签发一次性终端激活" width="600px">
      <el-form :model="issueForm" label-width="120px">
        <el-form-item label="组织 ID"><el-input-number v-model="issueForm.orgUnitId" :min="1" /></el-form-item>
        <el-form-item label="门店 ID"><el-input-number v-model="issueForm.storeId" :min="1" /></el-form-item>
        <el-form-item label="绑定用户 ID"><el-input-number v-model="issueForm.boundUserId" :min="1" /></el-form-item>
        <el-form-item label="终端模板"><el-input v-model="issueForm.terminalProfileCode" maxlength="64" /></el-form-item>
        <el-form-item label="有效期（秒）"><el-input-number v-model="issueForm.expiresInSeconds" :min="60" :max="86400" /></el-form-item>
      </el-form>
      <template #footer
        ><el-button @click="issueDialog = false">取消</el-button><el-button type="primary" @click="submitIssue">签发</el-button></template
      >
    </el-dialog>

    <el-dialog v-model="secretDialog" title="一次性秘密（关闭后无法再次查看）" width="720px" :close-on-click-modal="false">
      <el-alert type="error" :closable="false" show-icon>请立即交给受权实施人员并存入批准的密钥通道，禁止截图进工单或提交 Git。</el-alert>
      <el-descriptions :column="1" border class="mt-3">
        <el-descriptions-item label="用途">{{ shownSecret.purpose }}</el-descriptions-item>
        <el-descriptions-item label="对象 ID">{{ shownSecret.id }}</el-descriptions-item>
        <el-descriptions-item label="一次性秘密"
          ><el-text class="break-all" type="danger">{{ shownSecret.secret }}</el-text></el-descriptions-item
        >
      </el-descriptions>
      <template #footer><el-button type="primary" @click="secretDialog = false">我已安全保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup name="TerminalRegistry" lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus';
import { changeTerminalStatus, issueTerminalActivation, listTerminals, rotateTerminalCredential } from '@/api/terminal';
import type { TerminalPageVO, TerminalVO } from '@/api/terminal/types';

const loading = ref(false);
const queryStoreId = ref<number>();
const page = reactive<TerminalPageVO>({ items: [], total: 0, page: 1, size: 50 });
const issueDialog = ref(false);
const secretDialog = ref(false);
const shownSecret = reactive({ purpose: '', id: '', secret: '' });
const issueForm = reactive({ orgUnitId: 1, storeId: 1, boundUserId: 1, terminalProfileCode: 'ANDROID_POS_V1', expiresInSeconds: 600 });

const idempotencyKey = (purpose: string) => `${purpose}-${Date.now()}-${crypto.randomUUID()}`.slice(0, 64);
const statusType = (status: TerminalVO['status']) => (status === 'ACTIVE' ? 'success' : status === 'BLOCKED' ? 'warning' : 'danger');

const load = async () => {
  loading.value = true;
  try {
    const result = await listTerminals({ storeId: queryStoreId.value, page: page.page, size: page.size });
    Object.assign(page, result.data);
  } finally {
    loading.value = false;
  }
};

const openIssue = () => {
  issueForm.storeId = queryStoreId.value || issueForm.storeId;
  issueDialog.value = true;
};

const submitIssue = async () => {
  const result = await issueTerminalActivation({ ...issueForm, idempotencyKey: idempotencyKey('terminal-issue') });
  issueDialog.value = false;
  shownSecret.purpose = '终端激活';
  shownSecret.id = result.data.activationId;
  shownSecret.secret = result.data.activationSecret || '该幂等命令已处理，秘密不会再次显示；请取消后重新签发。';
  secretDialog.value = true;
};

const change = async (terminal: TerminalVO, targetStatus: TerminalVO['status']) => {
  const { value } = await ElMessageBox.prompt(`请输入将终端变更为 ${targetStatus} 的原因（至少 4 个字符）`, '终端安全状态', {
    inputValue: targetStatus === 'ACTIVE' ? '安全复核通过并批准解阻' : `按终端安全规范执行${targetStatus}`,
    inputValidator: (text) => (!!text && text.trim().length >= 4) || '原因至少 4 个字符'
  });
  await changeTerminalStatus(terminal.deviceId, {
    targetStatus,
    reason: value,
    idempotencyKey: idempotencyKey('terminal-status'),
    expectedVersion: terminal.recordVersion
  });
  ElMessage.success('终端状态已更新并记录审计');
  await load();
};

const rotate = async (terminal: TerminalVO) => {
  await ElMessageBox.confirm('旧凭据将立即失效。确认轮换？', '轮换终端凭据', { type: 'warning' });
  const result = await rotateTerminalCredential(terminal.deviceId);
  shownSecret.purpose = `设备凭据 v${result.data.credentialVersion}`;
  shownSecret.id = terminal.deviceId;
  shownSecret.secret = result.data.deviceCredential || '凭据不会再次显示，请重新执行受权轮换。';
  secretDialog.value = true;
  await load();
};

onMounted(load);
</script>
