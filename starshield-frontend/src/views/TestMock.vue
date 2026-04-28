<template>
  <div class="min-h-full bg-[#020617] px-6 pb-14 pt-8 md:px-11">
    <header class="mb-10 border-b border-white/10 pb-8">
      <p class="text-[11px] font-bold uppercase tracking-[0.22em] text-cyan-400/90">Aether Command · Load Test</p>
      <h1 class="mt-2 font-display text-2xl font-semibold text-slate-100">压测入口 · 发射控制台</h1>
      <p class="mt-2 max-w-xl text-sm text-slate-400">
        将并发请求发往星盾接入层，校验 MQ / 消费者在高压下的吞吐与稳定性。
      </p>
    </header>

    <div class="mx-auto max-w-3xl space-y-8">
      <section
        class="rounded-3xl border border-cyan-500/15 bg-gradient-to-br from-slate-900/85 to-[#070b18] p-8 shadow-[0_24px_64px_-20px_rgba(0,0,0,0.55)] backdrop-blur-[10px]"
      >
        <h2 class="font-display text-sm font-semibold uppercase tracking-[0.15em] text-slate-400">发射参数配置</h2>
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
      </section>

      <section class="flex justify-center">
        <button
          type="button"
          class="group relative flex w-full max-w-xl overflow-hidden rounded-2xl bg-gradient-to-r from-indigo-600 via-[#4e4af2] to-violet-600 px-8 py-5 text-center text-[15px] font-semibold tracking-wide text-white shadow-[0_20px_50px_-12px_rgba(78,74,242,0.65)] ring-2 ring-white/25 transition hover:brightness-105 disabled:opacity-85"
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
            <span class="material-symbols-outlined text-2xl text-emerald-200">verified</span>
            测试完成 · 点击再次发射
          </span>
        </button>
      </section>

      <section
        v-if="stats.done || running"
        class="rounded-3xl border border-white/10 bg-slate-900/65 p-8 shadow-xl backdrop-blur-[10px] ring-1 ring-white/[0.06]"
      >
        <h2 class="font-display text-sm font-semibold uppercase tracking-[0.15em] text-slate-400">实时统计</h2>
        <div class="mt-8 grid gap-4 sm:grid-cols-3 lg:grid-cols-6">
          <div
            v-for="cell in statsCells"
            :key="cell.key"
            class="rounded-2xl border border-white/[0.08] bg-slate-950/60 px-4 py-4 text-center shadow-inner"
          >
            <div class="font-display text-2xl font-bold tracking-tight" :class="cell.colorClass">{{ cell.val }}</div>
            <div class="mt-1 text-[10px] font-semibold uppercase tracking-wide text-slate-500">{{ cell.label }}</div>
          </div>
        </div>
        <div class="mt-8">
          <div class="mb-2 h-2 overflow-hidden rounded-full bg-slate-800/90">
            <div
              class="h-full rounded-full bg-gradient-to-r from-[#4e4af2] via-indigo-500 to-emerald-400 transition-[width] duration-300"
              :style="{ width: `${progressPercent}%` }"
            />
          </div>
          <p class="text-right text-xs font-medium text-slate-500">{{ progressPercent }}%</p>
        </div>
      </section>

      <section
        v-if="logs.length > 0"
        class="overflow-hidden rounded-3xl bg-slate-950 text-left ring-2 ring-slate-800 shadow-2xl"
      >
        <div class="flex items-center justify-between border-b border-slate-700/70 bg-slate-900 px-6 py-3">
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
        <div ref="logContainer" class="max-h-[320px] overflow-y-auto px-6 py-4 font-mono text-[11px] leading-relaxed">
          <div
            v-for="(entry, i) in logs"
            :key="i"
            class="border-b border-slate-800/60 py-1.5 text-slate-300 last:border-0"
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
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref, nextTick } from 'vue'
import { uploadChatMessage } from '../api/chat.js'

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
    colorClass: 'text-emerald-400'
  },
  {
    key: 'fail',
    val: stats.fail,
    label: '失败',
    colorClass: 'text-rose-400'
  },
  {
    key: 'sent',
    val: stats.success + stats.fail,
    label: '已发送',
    colorClass: 'text-cyan-300'
  },
  {
    key: 'time',
    val: `${stats.duration} ms`,
    label: '耗时',
    colorClass: 'text-amber-300'
  },
  {
    key: 'qps',
    val: stats.qps,
    label: 'QPS（估）',
    colorClass: 'text-violet-300'
  },
  {
    key: 'rate',
    val: `${stats.successRate}%`,
    label: '成功率',
    colorClass: parseFloat(stats.successRate) < 90 ? 'text-rose-400' : 'text-emerald-400'
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
