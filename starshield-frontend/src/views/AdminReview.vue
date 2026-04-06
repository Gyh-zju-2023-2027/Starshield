<template>
  <div class="admin-wrap">
    <div class="panel">
      <h2>人工复核队列</h2>
      <div class="toolbar">
        <el-button type="primary" @click="loadData">刷新</el-button>
      </div>

      <el-table :data="rows" stripe height="420" @selection-change="onSelectionChange" @row-click="openDetail">
        <el-table-column type="selection" width="48" />
        <el-table-column prop="id" label="ID" width="180" />
        <el-table-column prop="playerId" label="玩家ID" width="140" />
        <el-table-column prop="riskScore" label="风险分" width="90" />
        <el-table-column prop="labels" label="标签" width="180" />
        <el-table-column prop="content" label="发言内容" min-width="320" show-overflow-tooltip />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="scope">
            <el-button type="danger" size="small" @click.stop="onBan(scope.row)">确认封禁</el-button>
            <el-button type="success" size="small" @click.stop="onRelease(scope.row)">解除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="batch-bar">
        <span>已选 {{ selected.length }} 条</span>
        <el-button type="danger" plain :disabled="!selected.length" @click="onBatchBan">批量封禁</el-button>
        <el-button type="success" plain :disabled="!selected.length" @click="onBatchRelease">批量解除</el-button>
      </div>
    </div>

    <el-drawer v-model="drawerVisible" title="记录详情" size="45%">
      <div v-if="current">
        <p><b>玩家ID：</b>{{ current.playerId }}</p>
        <p><b>决策：</b>{{ current.decision }}</p>
        <p><b>风险分：</b>{{ current.riskScore }}</p>
        <p><b>标签：</b>{{ current.labels }}</p>
        <p><b>命中词：</b>{{ current.hitWords }}</p>
        <p><b>AI结果：</b>{{ current.aiAnalysisResult }}</p>
        <p><b>原文：</b>{{ current.content }}</p>

        <div class="timeline-block">
          <h4>操作时间线</h4>
          <el-timeline>
            <el-timeline-item
              v-for="item in auditLogs"
              :key="item.id"
              :timestamp="item.createTime"
              :type="item.action === 'CONFIRM_BAN' ? 'danger' : 'success'"
            >
              <div class="line-main">
                <span>{{ item.operator }}</span>
                <span>{{ item.action }}</span>
                <span>{{ item.beforeDecision }} → {{ item.afterDecision }}</span>
                <span>{{ item.beforeRiskScore }} → {{ item.afterRiskScore }}</span>
              </div>
            </el-timeline-item>
          </el-timeline>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { confirmBan, fetchAuditLogs, fetchPending, releaseRecord } from '../api/admin'

const rows = ref([])
const selected = ref([])
const drawerVisible = ref(false)
const current = ref(null)
const auditLogs = ref([])

async function loadData() {
  const res = await fetchPending(1, 50)
  if (res.code === 200) {
    rows.value = res.data || []
  }
}

function onSelectionChange(list) {
  selected.value = list || []
}

async function openDetail(row) {
  current.value = row
  drawerVisible.value = true
  const res = await fetchAuditLogs(row.id, 30)
  if (res.code === 200) {
    auditLogs.value = res.data || []
  } else {
    auditLogs.value = []
  }
}

async function onBan(row) {
  const ok = await ElMessageBox.confirm('确认封禁该记录？', '提示', { type: 'warning' })
    .then(() => true)
    .catch(() => false)
  if (!ok) return

  const res = await confirmBan(row.id)
  if (res.code === 200) {
    ElMessage.success('已确认封禁')
    await loadData()
    if (current.value?.id === row.id) {
      await openDetail(row)
    }
  }
}

async function onRelease(row) {
  const ok = await ElMessageBox.confirm('确认解除该记录？', '提示', { type: 'warning' })
    .then(() => true)
    .catch(() => false)
  if (!ok) return

  const res = await releaseRecord(row.id)
  if (res.code === 200) {
    ElMessage.success('已解除')
    await loadData()
    if (current.value?.id === row.id) {
      await openDetail(row)
    }
  }
}

async function onBatchBan() {
  const snapshot = JSON.parse(JSON.stringify(rows.value))
  for (const row of selected.value) {
    row.decision = 'BLOCK'
  }
  try {
    for (const row of selected.value) {
      const res = await confirmBan(row.id)
      if (res.code !== 200) throw new Error('批量封禁失败')
    }
    ElMessage.success('批量封禁成功')
    await loadData()
  } catch (e) {
    rows.value = snapshot
    ElMessage.error('批量封禁失败，已回滚')
  }
}

async function onBatchRelease() {
  const snapshot = JSON.parse(JSON.stringify(rows.value))
  for (const row of selected.value) {
    row.decision = 'PASS'
  }
  try {
    for (const row of selected.value) {
      const res = await releaseRecord(row.id)
      if (res.code !== 200) throw new Error('批量解除失败')
    }
    ElMessage.success('批量解除成功')
    await loadData()
  } catch (e) {
    rows.value = snapshot
    ElMessage.error('批量解除失败，已回滚')
  }
}

onMounted(loadData)
</script>

<style scoped>
.admin-wrap {
  padding: 16px;
  background: #080c16;
  min-height: 100vh;
  color: #dce7ff;
}

.panel {
  border: 1px solid #1e2b45;
  border-radius: 12px;
  padding: 16px;
  background: linear-gradient(160deg, #0d1426 0%, #0b101d 100%);
}

.toolbar {
  margin: 12px 0;
}

.batch-bar {
  margin-top: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.timeline-block {
  margin-top: 18px;
  border-top: 1px solid #22335a;
  padding-top: 10px;
}

.line-main {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
</style>
