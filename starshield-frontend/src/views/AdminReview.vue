<template>
  <div class="min-h-full bg-[#020617] px-6 pb-14 pt-9 md:px-11">
    <!-- 审核后台：标题 + 说明 -->
    <header class="mb-8 flex flex-wrap items-start justify-between gap-6 border-b border-white/10 pb-7">
      <div class="max-w-3xl">
        <p class="text-[11px] font-bold uppercase tracking-[0.22em] text-cyan-400/90">Aether Command · Moderation</p>
        <h1 class="mt-2 font-display text-2xl font-semibold tracking-tight text-slate-100">待审核队列</h1>
        <p class="mt-2 text-sm leading-relaxed text-slate-400">
          当前有风险记录待处理，请及时审核并采取封禁或解除动作。
        </p>
      </div>
      <div class="rounded-xl border border-cyan-500/25 bg-cyan-950/40 px-5 py-3 text-right ring-1 ring-cyan-500/10">
        <p class="font-display text-2xl font-bold text-cyan-200">{{ rows.length }}</p>
        <p class="text-[11px] font-semibold uppercase tracking-wide text-cyan-400/90">待处理条目</p>
      </div>
    </header>

    <div
      class="rounded-3xl border border-white/10 bg-slate-900/70 p-7 shadow-xl backdrop-blur-[10px] ring-1 ring-white/[0.06]"
    >
      <div class="mb-6 flex flex-wrap items-center justify-between gap-4 border-b border-white/10 pb-5">
        <div class="flex flex-wrap items-center gap-2">
          <el-button plain round disabled class="!font-medium">
            <span class="material-symbols-outlined mr-1 align-middle text-lg leading-none">filter_list</span>
            筛选
          </el-button>
        </div>
        <div class="flex flex-wrap gap-2">
          <el-button type="primary" round @click="loadData">
            <span class="material-symbols-outlined mr-1 align-middle text-lg leading-none">refresh</span>
            刷新
          </el-button>
          <el-button type="danger" plain round :disabled="!selected.length" @click="onBatchBan">
            <span class="material-symbols-outlined mr-1 align-middle text-lg leading-none">block</span>
            批量封禁
          </el-button>
          <el-button type="success" plain round :disabled="!selected.length" @click="onBatchRelease">
            <span class="material-symbols-outlined mr-1 align-middle text-lg leading-none">undo</span>
            批量解除
          </el-button>
        </div>
      </div>

      <el-table
        :data="rows"
        stripe
        height="440"
        class="st-table"
        @selection-change="onSelectionChange"
        @row-click="openDetail"
      >
        <el-table-column type="selection" width="48" />
        <el-table-column prop="id" label="记录 ID" width="180" />
        <el-table-column prop="playerId" label="玩家 ID" width="140" />
        <el-table-column prop="riskScore" label="风险分" width="96" />
        <el-table-column prop="labels" label="标签" width="170" />
        <el-table-column prop="content" label="摘要" min-width="300" show-overflow-tooltip />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="scope">
            <el-button link type="danger" size="small" @click.stop="onBan(scope.row)">
              <span class="material-symbols-outlined text-base">gavel</span>
              封禁
            </el-button>
            <el-button link type="success" size="small" @click.stop="onRelease(scope.row)">
              <span class="material-symbols-outlined text-base">check_circle</span>
              解除
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="mt-5 flex flex-wrap items-center gap-4 rounded-2xl border border-white/[0.08] bg-slate-950/50 px-5 py-3 text-sm text-slate-400 ring-1 ring-white/[0.04]">
        <span class="material-symbols-outlined text-xl text-cyan-500/90">checklist</span>
        <span>已选中 <b class="text-slate-200">{{ selected.length }}</b> 条</span>
        <span class="text-slate-600">｜</span>
        <span class="text-xs text-slate-500">双击行或使用操作列进行管理</span>
      </div>
    </div>

    <el-drawer v-model="drawerVisible" title="审计详情 · Record Detail" size="46%" destroy-on-close>
      <div v-if="current" class="font-sans text-slate-200">
        <section class="space-y-3 rounded-2xl border border-white/10 bg-slate-900/80 p-4 text-sm ring-1 ring-white/[0.06]">
          <div class="flex justify-between border-b border-white/10 pb-2"><span class="text-slate-500">玩家</span><span class="font-medium text-slate-100">{{ current.playerId }}</span></div>
          <div class="flex justify-between border-b border-white/10 pb-2"><span class="text-slate-500">决策</span><span class="font-mono text-slate-200">{{ current.decision }}</span></div>
          <div class="flex justify-between border-b border-white/10 pb-2"><span class="text-slate-500">风险</span><span>{{ current.riskScore }}</span></div>
          <div class="flex justify-between pb-2"><span class="text-slate-500">标签</span><span>{{ current.labels }}</span></div>
        </section>

        <p class="mt-5 text-xs font-semibold uppercase tracking-[0.15em] text-slate-500">命中词</p>
        <p class="mt-2 text-sm leading-relaxed text-slate-300">{{ current.hitWords || '—' }}</p>

        <p class="mt-6 text-xs font-semibold uppercase tracking-[0.15em] text-slate-500">原文内容</p>
        <div class="mt-2 whitespace-pre-wrap rounded-2xl border border-white/10 bg-slate-950/70 p-4 text-sm leading-relaxed text-slate-300">{{ current.content }}</div>

        <div class="mt-8 rounded-3xl border border-white/10 bg-slate-900/85 p-4 ring-1 ring-white/[0.06]">
          <div class="mb-4 flex items-center gap-2">
            <span class="material-symbols-outlined text-cyan-400">schedule</span>
            <h3 class="font-display text-lg font-semibold text-slate-100">Audit Timeline</h3>
          </div>
          <el-timeline>
            <el-timeline-item
              v-for="item in auditLogs"
              :key="item.id"
              :timestamp="item.createTime"
              placement="top"
              :type="item.action === 'CONFIRM_BAN' ? 'danger' : 'success'"
            >
              <div class="line-main flex flex-wrap gap-x-3 gap-y-1 text-xs text-slate-400">
                <span class="font-medium text-slate-200">{{ item.operator }}</span>
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
:deep(.st-table.el-table) {
  --el-table-bg-color: #0f172a;
  --el-table-tr-bg-color: #0f172a;
  --el-table-header-bg-color: #020617;
  --el-table-header-text-color: #94a3b8;
  --el-table-text-color: #e2e8f0;
  --el-table-row-hover-bg-color: rgba(34, 211, 238, 0.06);
  --el-table-border-color: rgba(148, 163, 184, 0.12);
}
:deep(.st-table.el-table--striped .el-table__body tr.el-table__row--striped td) {
  background: rgba(15, 23, 42, 0.55);
}
</style>
