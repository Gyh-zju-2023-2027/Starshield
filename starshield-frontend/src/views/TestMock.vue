<template>
  <div class="ss-page">
    <PageIntro
      eyebrow="Aether Command · Load Test"
      title="压测入口"
      description="在前端直接配置并发批次、平台来源和语料模式，快速观察接入层在压力下的吞吐、稳定性与返回日志。"
    >
      <template #meta>
        <div class="grid min-w-[240px] grid-cols-2 gap-3">
          <MetricCard label="总请求数" :value="config.total" helper="单次发射规模" icon="bolt" />
          <MetricCard label="批大小" :value="config.batchSize" helper="单批并发量" icon="stacks" accent="amber" />
        </div>
      </template>
    </PageIntro>

    <div class="space-y-6">
      <SurfacePanel>
        <div class="mb-6">
          <h2 class="ss-section-title">发射参数配置</h2>
          <p class="ss-section-copy">建议先用内置随机语料验证链路，再切换到真实文案回放，避免第一轮测试就把问题藏起来。</p>
        </div>
        <div class="mt-8 grid gap-8 sm:grid-cols-3">
          <div class="flex flex-col gap-2">
            <label class="text-[11px] font-bold uppercase tracking-wide text-slate-500">并发请求总数</label>
            <el-input-number
              v-model="config.total"
              :min="1"
              :max="5000"
              :step="100"
              :disabled="running"
              class="w-full"
              controls-position="right"
            />
          </div>
          <div class="flex flex-col gap-2">
            <label class="text-[11px] font-bold uppercase tracking-wide text-slate-500">批次大小</label>
            <el-input-number
              v-model="config.batchSize"
              :min="1"
              :max="200"
              :step="10"
              :disabled="running"
              class="w-full"
              controls-position="right"
            />
          </div>
          <div class="flex flex-col gap-2">
            <label class="text-[11px] font-bold uppercase tracking-wide text-slate-500">来源平台</label>
            <el-select v-model="config.platform" :disabled="running" class="w-full">
              <el-option label="游戏内聊天" value="GAME_INNER" />
              <el-option label="B站弹幕" value="BILIBILI" />
              <el-option label="微博评论" value="WEIBO" />
              <el-option label="抖音直播" value="DOUYIN" />
            </el-select>
          </div>
        </div>

        <div class="mt-8 flex flex-col gap-4 border-t border-white/10 pt-8">
          <label class="text-[11px] font-bold uppercase tracking-wide text-slate-500">发言内容来源</label>
          <el-radio-group v-model="contentMode" :disabled="running" class="flex flex-wrap gap-2">
            <el-radio-button value="pool">内置随机话术（压测）</el-radio-button>
            <el-radio-button value="lines">粘贴真实文案（每行一条）</el-radio-button>
          </el-radio-group>
          <p v-if="contentMode === 'lines'" class="text-xs leading-relaxed text-slate-500">
            将抓取粘贴的每一条真实发言按序循环发往接入 API，完整经过引擎 A（布隆敏感词）、轻量模型与 DeepSeek（需配置密钥与 Flask 评分服务）。
          </p>
          <el-input
            v-if="contentMode === 'lines'"
            v-model="realLinesText"
            type="textarea"
            :rows="9"
            :disabled="running"
            placeholder="从弹幕/评论区/工单导出等处复制：每行一条。条数少于总发射数时将循环使用。"
            class="!font-mono text-[13px] leading-relaxed"
          />
        </div>
      </SurfacePanel>

      <section>
        <button
          type="button"
          class="ss-button-primary group relative flex w-full overflow-hidden rounded-[28px] px-8 py-5 text-center text-[15px] font-semibold tracking-wide text-white transition hover:brightness-105 disabled:opacity-85"
          :disabled="running"
          @click="startTest"
        >
          <span
            v-if="!running && !stats.done"
            class="inline-flex w-full items-center justify-center gap-2"
          >
            <span class="material-symbols-outlined text-2xl group-hover:scale-110">rocket_launch</span>
            {{ launchButtonLabel }}
          </span>
          <span v-else-if="running" class="inline-flex w-full items-center justify-center gap-3">
            <span class="inline-block h-5 w-5 animate-spin rounded-full border-2 border-white/40 border-t-white" />
            正在发射中… {{ stats.success + stats.fail }} / {{ config.total }}
          </span>
          <span v-else class="inline-flex w-full items-center justify-center gap-2">
            <span class="material-symbols-outlined text-2xl text-sky-100">verified</span>
            测试完成 · 点击再次发射
          </span>
        </button>
      </section>

      <SurfacePanel v-if="stats.done || running">
        <div class="mb-5">
          <h2 class="ss-section-title">实时统计</h2>
          <p class="ss-section-copy">这里是当前这一轮压测的即时结果。异常响应会同步落到下方日志流。</p>
        </div>
        <div class="grid gap-4 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-6">
          <MetricCard
            v-for="cell in statsCells"
            :key="cell.key"
            :label="cell.label"
            :value="cell.val"
            :accent="cell.accent"
          />
        </div>
        <div class="mt-8">
          <div class="mb-2 h-2 overflow-hidden rounded-full bg-slate-800/90">
            <div
              class="h-full rounded-full bg-[linear-gradient(90deg,#3b82f6_0%,#5ee6ff_100%)] transition-[width] duration-300"
              :style="{ width: `${progressPercent}%` }"
            />
          </div>
          <p class="text-right text-xs font-medium text-slate-500">{{ progressPercent }}%</p>
        </div>
      </SurfacePanel>

      <SurfacePanel
        v-if="logs.length > 0"
        tone="muted"
      >
        <div class="-mx-6 -mt-6 flex items-center justify-between border-b border-slate-700/70 bg-slate-900/80 px-6 py-4">
          <div class="flex items-center gap-2 text-emerald-300">
            <span class="material-symbols-outlined">terminal</span>
            <span class="font-display text-xs font-semibold uppercase tracking-[0.2em]">Request Log Stream</span>
          </div>
          <button
            type="button"
            class="rounded-lg border border-slate-600 px-3 py-1 text-xs font-medium text-slate-400 hover:bg-slate-800 hover:text-white"
            @click="logs = []"
          >
            清空
          </button>
        </div>
        <div ref="logContainer" class="ss-scrollbar max-h-[320px] overflow-y-auto px-1 pt-5 font-mono text-[11px] leading-relaxed">
          <div
            v-for="(entry, i) in logs"
            :key="i"
            class="border-b border-slate-800/60 px-1 py-2 text-slate-300 last:border-0"
            :class="{
              '!text-emerald-400': entry.type === 'success',
              '!text-amber-300': entry.type === 'warn',
              '!text-rose-400': entry.type === 'error'
            }"
          >
            <span class="mr-4 text-slate-500">{{ entry.time }}</span>
            <span>{{ entry.msg }}</span>
          </div>
        </div>
      </SurfacePanel>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref, nextTick } from 'vue'
import { uploadChatMessage } from '../api/chat.js'
import MetricCard from '../components/ui/MetricCard.vue'
import PageIntro from '../components/ui/PageIntro.vue'
import SurfacePanel from '../components/ui/SurfacePanel.vue'

const contentMode = ref('pool')
const realLinesText = ref('')

const config = reactive({
  total: 1000,
  batchSize: 50,
  platform: 'GAME_INNER'
})

const running = ref(false)

const stats = reactive({
  success: 0,
  fail: 0,
  duration: 0,
  qps: 0,
  successRate: '0.00',
  done: false
})

const logs = ref([])
const logContainer = ref(null)

const progressPercent = computed(() => {
  const total = config.total
  if (total === 0) return 0
  return Math.min(100, Math.round(((stats.success + stats.fail) / total) * 100))
})

const launchButtonLabel = computed(() => {
  if (contentMode.value === 'lines') {
    return `用真实文案发送 ${config.total} 条（接入层入库 + 分析链路）`
  }
  return `模拟发送 ${config.total} 条并发日志`
})

const statsCells = computed(() => [
  {
    key: 'ok',
    val: stats.success,
    label: '成功',
    accent: 'cyan'
  },
  {
    key: 'fail',
    val: stats.fail,
    label: '失败',
    accent: 'rose'
  },
  {
    key: 'sent',
    val: stats.success + stats.fail,
    label: '已发送',
    accent: 'amber'
  },
  {
    key: 'time',
    val: `${stats.duration} ms`,
    label: '耗时',
    accent: 'amber'
  },
  {
    key: 'qps',
    val: stats.qps,
    label: 'QPS（估）',
    accent: 'cyan'
  },
  {
    key: 'rate',
    val: `${stats.successRate}%`,
    label: '成功率',
    accent: parseFloat(stats.successRate) < 90 ? 'rose' : 'cyan'
  }
])

function randomPlayerId() {
  return 'P' + Math.floor(Math.random() * 9000000 + 1000000)
}

const contentPool = [
  '这把操作太猛了！',
  '主播你好厉害！',
  '求组队一起打boss',
  '这个装备怎么搭配？',
  '服务器卡不卡',
  '今天状态不错冲！'
]

function randomContent() {
  return contentPool[Math.floor(Math.random() * contentPool.length)]
}

function parseRealLines() {
  return realLinesText.value
    .split('\n')
    .map((s) => s.trim())
    .filter((s) => s.length > 0)
}

function resolveContent(index) {
  if (contentMode.value === 'lines') {
    const lines = parseRealLines()
    if (lines.length === 0) {
      return null
    }
    return lines[index % lines.length]
  }
  return randomContent()
}

function addLog(type, msg) {
  const now = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  logs.value.push({ type, time: now, msg })
  if (logs.value.length > 500) {
    logs.value.splice(0, logs.value.length - 500)
  }
  nextTick(() => {
    if (logContainer.value) {
      logContainer.value.scrollTop = logContainer.value.scrollHeight
    }
  })
}

async function startTest() {
  if (contentMode.value === 'lines' && parseRealLines().length === 0) {
    addLog('error', '「真实文案」模式需要先粘贴至少一行内容。')
    return
  }

  running.value = true
  stats.success = 0
  stats.fail = 0
  stats.duration = 0
  stats.qps = 0
  stats.successRate = '0.00'
  stats.done = false
  logs.value = []

  const modeHint =
    contentMode.value === 'lines'
      ? `真实文案模式，${parseRealLines().length} 条不重复语料循环使用`
      : '内置随机话术'
  addLog('info', `开始：共 ${config.total} 条，批次 ${config.batchSize}，${modeHint}`)

  const startTime = Date.now()
  const total = config.total
  const batchSize = config.batchSize

  for (let i = 0; i < total; i += batchSize) {
    const batchEnd = Math.min(i + batchSize, total)
    const batchPromises = []

    for (let j = i; j < batchEnd; j++) {
      const text = resolveContent(j)
      if (text === null) {
        addLog('error', '真实文案不可用，中止。')
        running.value = false
        stats.done = true
        return
      }
      const payload = {
        playerId: randomPlayerId(),
        content: text,
        platform: config.platform,
        status: 0
      }

      const p = uploadChatMessage(payload)
        .then((res) => {
          if (res && res.code === 200) {
            stats.success++
          } else {
            stats.fail++
            addLog('warn', `请求 #${j + 1} 响应异常: ${JSON.stringify(res)}`)
          }
        })
        .catch((err) => {
          stats.fail++
          addLog('error', `请求 #${j + 1} 失败: ${err.message}`)
        })

      batchPromises.push(p)
    }

    await Promise.all(batchPromises)

    addLog('info', `批次完成：${batchEnd}/${total}，成功 ${stats.success}，失败 ${stats.fail}`)
  }

  const endTime = Date.now()
  stats.duration = endTime - startTime
  stats.qps = Math.round((total / stats.duration) * 1000)
  stats.successRate = ((stats.success / total) * 100).toFixed(2)
  stats.done = true
  running.value = false

  addLog(
    stats.fail === 0 ? 'success' : 'warn',
    `测试完成！总耗时 ${stats.duration}ms，QPS=${stats.qps}，成功率 ${stats.successRate}%`
  )
}
</script>

