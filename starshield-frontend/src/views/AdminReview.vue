<template>
  <div class="ss-page">
    <PageIntro
      eyebrow="Aether Command · Moderation"
      title="待审核队列"
      description="这里是运营同学的处理工作面。我们把风险记录、理由标签、批量动作和审计时间线都收在同一条流里。"
    >
      <template #meta>
        <MetricCard label="待处理条目" :value="rows.length" helper="当前待复核队列" icon="fact_check" />
      </template>
    </PageIntro>

    <SurfacePanel>
      <div class="ss-toolbar">
        <div class="flex flex-wrap items-center gap-2">
          <span class="ss-chip">人工复核</span>
          <span class="ss-chip">批量动作</span>
          <span class="ss-chip">审计时间线</span>
        </div>
        <div class="flex flex-wrap gap-2">
          <el-button plain round disabled class="!border-white/10 !bg-white/[0.03] !font-medium !text-slate-400">
            <span class="material-symbols-outlined mr-1 align-middle text-lg leading-none">filter_list</span>
            筛选
          </el-button>
          <el-button type="primary" round @click="loadData">
            <span class="material-symbols-outlined mr-1 align-middle text-lg leading-none">refresh</span>
            刷新
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
        <el-table-column 
          prop="reasonTag" 
          label="理由" 
          width="100"
          :filters="reasonFilters"
          :filter-method="filterReason"
        >
          <template #default="{ row }">
            <el-tag v-if="row.reasonTag" :color="getReasonColor(row.reasonTag)" effect="dark" size="small" class="border-none">
              {{ getReasonLabel(row.reasonTag) }}
            </el-tag>
          </template>
        </el-table-column>
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

      <div v-if="selected.length > 0" class="fixed bottom-6 left-1/2 z-50 flex -translate-x-1/2 items-center gap-4 rounded-full border border-white/10 bg-slate-900/90 px-6 py-3 shadow-2xl backdrop-blur-md ring-1 ring-white/5">
        <span class="text-sm font-medium text-slate-300">
          已选 <b class="text-cyan-400">{{ selected.length }}</b> 条
        </span>
        <div class="h-4 w-[1px] bg-white/20"></div>
        <el-button type="danger" round size="small" @click="openReasonDialog('BLOCK')">
          批量违规
        </el-button>
        <el-button type="success" round size="small" @click="openReasonDialog('PASS')">
          批量正常
        </el-button>
        <el-button type="warning" round size="small" plain @click="openReasonDialog('REVIEW')">
          加入观察名单
        </el-button>
      </div>
    </SurfacePanel>

    <el-dialog v-model="reasonDialogVisible" title="选择理由" width="400px" custom-class="!bg-slate-900 !border !border-white/10" destroy-on-close>
      <div class="flex flex-wrap gap-2 mb-4">
        <el-button
          v-for="tag in reasonTags"
          :key="tag.value"
          :color="tag.color"
          :plain="selectedReasonValue !== tag.value"
          size="small"
          class="!border-none"
          @click="selectedReasonValue = tag.value; customReason = ''"
        >
          {{ tag.label }}
        </el-button>
      </div>
      <el-input 
        v-model="customReason" 
        placeholder="自定义理由..." 
        size="small" 
        class="mb-4" 
        @input="selectedReasonValue = 'custom'"
      />
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="reasonDialogVisible = false" size="small" plain>取消</el-button>
          <el-button type="primary" @click="confirmBatchWithReason" size="small" :disabled="!selectedReasonValue && !customReason">
            确认
          </el-button>
        </span>
      </template>
    </el-dialog>

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
import { onMounted, ref, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { confirmBan, fetchAuditLogs, fetchPending, releaseRecord, batchProcess } from '../api/admin'
import MetricCard from '../components/ui/MetricCard.vue'
import PageIntro from '../components/ui/PageIntro.vue'
import SurfacePanel from '../components/ui/SurfacePanel.vue'

const rows = ref([])
const selected = ref([])
const drawerVisible = ref(false)
const current = ref(null)
const auditLogs = ref([])

const reasonDialogVisible = ref(false)
const selectedReasonValue = ref('')
const customReason = ref('')
const currentBatchDecision = ref('')

const reasonTags = [
  { label: '辱骂', value: 'abuse', color: '#ef4444' },
  { label: '广告', value: 'ad', color: '#f59e0b' },
  { label: '涉政', value: 'politics', color: '#b91c1c' },
  { label: '外挂', value: 'cheat', color: '#8b5cf6' },
  { label: '刷屏', value: 'spam', color: '#64748b' },
  { label: '引战', value: 'flame', color: '#f97316' },
  { label: '色情', value: 'porn', color: '#ec4899' },
  { label: '隐私', value: 'privacy', color: '#14b8a6' },
  { label: '诈骗', value: 'scam', color: '#eab308' }
]

const reasonFilters = computed(() => {
  return reasonTags.map(t => ({ text: t.label, value: t.value }))
})

function filterReason(value, row) {
  return row.reasonTag === value
}

function getReasonColor(val) {
  const tag = reasonTags.find(t => t.value === val)
  return tag ? tag.color : '#334155'
}

function getReasonLabel(val) {
  const tag = reasonTags.find(t => t.value === val)
  return tag ? tag.label : val
}

function openReasonDialog(decision) {
  currentBatchDecision.value = decision
  selectedReasonValue.value = ''
  customReason.value = ''
  reasonDialogVisible.value = true
}

async function confirmBatchWithReason() {
  const finalReason = selectedReasonValue.value === 'custom' ? customReason.value : (selectedReasonValue.value || customReason.value)
  const ids = selected.value.map(r => r.id)
  
  try {
    const res = await batchProcess(ids, currentBatchDecision.value, finalReason)
    if (res.code === 200) {
      ElMessage.success('批量处理成功')
    } else if (res.code === 207) {
      ElMessage.warning('部分记录处理失败')
    } else {
      throw new Error(res.message || '批量处理失败')
    }
    reasonDialogVisible.value = false
    await loadData()
  } catch (e) {
    ElMessage.error(`请求失败：${e?.response?.data?.message || e?.message || e}`)
  }
}

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

  try {
    const res = await confirmBan(row.id)
    if (res.code === 200) {
      ElMessage.success('已确认封禁')
      await loadData()
      if (current.value?.id === row.id) {
        await openDetail(row)
      }
    } else {
      ElMessage.error(res?.message || `操作失败 (code=${res?.code})`)
    }
  } catch (e) {
    ElMessage.error(`请求失败：${e?.response?.data?.message || e?.message || e}`)
  }
}

async function onRelease(row) {
  const ok = await ElMessageBox.confirm('确认解除该记录？', '提示', { type: 'warning' })
    .then(() => true)
    .catch(() => false)
  if (!ok) return

  try {
    const res = await releaseRecord(row.id)
    if (res.code === 200) {
      ElMessage.success('已解除')
      await loadData()
      if (current.value?.id === row.id) {
        await openDetail(row)
      }
    } else {
      ElMessage.error(res?.message || `操作失败 (code=${res?.code})`)
    }
  } catch (e) {
    ElMessage.error(`请求失败：${e?.response?.data?.message || e?.message || e}`)
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
