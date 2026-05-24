<template>
  <div class="ss-page">
    <PageIntro
      eyebrow="Aether Command · Daily Report"
      title="每日战报"
      description="按日期查看日报数据，包括摘要、趋势、关键词和典型案例。"
    >
      <template #meta>
        <div class="grid min-w-[260px] grid-cols-2 gap-3">
          <MetricCard label="报告日期" :value="dateString || '--'" helper="当前工作日" icon="calendar_month" />
          <MetricCard
            label="导出状态"
            :value="exporting ? '导出中' : reportData ? '就绪' : '待数据'"
            helper="A4 PDF"
            icon="picture_as_pdf"
            accent="amber"
          />
        </div>
      </template>
    </PageIntro>

    <div class="grid items-stretch gap-6 xl:grid-cols-[360px_minmax(0,1fr)]">
      <SurfacePanel class="h-fit">
        <div class="mb-5 flex items-start justify-between gap-3">
          <div>
            <h2 class="ss-section-title">快速选日期</h2>
            <p class="ss-section-copy">选择日期后加载对应日报数据。</p>
          </div>
          <span class="ss-chip ss-chip--accent">Daily</span>
        </div>

        <div class="grid gap-3">
          <el-date-picker
            v-model="selectedDate"
            type="date"
            class="w-full"
            placeholder="选择日期"
            :shortcuts="dateShortcuts"
            :disabled-date="disableFutureDate"
          />

          <div class="grid grid-cols-3 gap-2">
            <el-button plain class="!border-white/10 !bg-white/[0.03] !text-slate-300" @click="shiftDate(-1)">
              前一天
            </el-button>
            <el-button plain class="!border-sky-400/18 !bg-sky-400/[0.10] !text-sky-50" @click="jumpToToday">
              今天
            </el-button>
            <el-button
              plain
              class="!border-white/10 !bg-white/[0.03] !text-slate-300"
              :disabled="isTodaySelected"
              @click="shiftDate(1)"
            >
              后一天
            </el-button>
          </div>
        </div>

        <div class="mt-6">
          <h3 class="text-xs font-semibold uppercase tracking-[0.14em] text-slate-500">最近两周</h3>
          <div class="mt-3 grid grid-cols-2 gap-2">
            <button
              v-for="item in recentDates"
              :key="item.key"
              type="button"
              class="rounded-2xl border px-3 py-3 text-left transition"
              :class="
                isSameDay(selectedDate, item.date)
                  ? 'border-sky-400/18 bg-sky-400/[0.10] text-sky-50'
                  : 'border-white/10 bg-white/[0.03] text-slate-300 hover:border-white/16 hover:bg-white/[0.05]'
              "
              @click="selectedDate = item.date"
            >
              <div class="text-sm font-semibold">{{ item.label }}</div>
              <div class="mt-1 text-xs text-slate-500">{{ item.subLabel }}</div>
            </button>
          </div>
        </div>
      </SurfacePanel>

      <div class="ss-report-loading flex h-full min-w-0 rounded-[28px]" v-loading="loading">
        <SurfacePanel v-if="reportData" tone="muted" class="w-full">
          <div id="pdf-report-content" class="flex flex-col gap-6">
            <div class="flex flex-wrap items-center justify-between gap-4 border-b border-white/10 pb-5">
              <div>
                <p class="text-[11px] font-semibold uppercase tracking-[0.18em] text-sky-200">Daily Intelligence</p>
                <h2 class="mt-2 font-display text-[28px] font-semibold tracking-tight text-slate-50">星盾治理战报</h2>
                <p class="mt-2 text-sm text-slate-400">{{ dateString }} 数据快照</p>
              </div>
              <div class="flex items-center gap-3">
                <span class="ss-chip">{{ reportData.totalCount }} 条记录</span>
                <el-button
                  type="primary"
                  class="ss-button-primary !border-none !px-5 !py-5 !font-semibold"
                  data-html2canvas-ignore="true"
                  :loading="exporting"
                  @click="exportPdf"
                >
                  导出 PDF
                </el-button>
              </div>
            </div>

            <div class="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
              <MetricCard label="总消息量" :value="reportData.totalCount" helper="当日接入总量" icon="forum" />
              <MetricCard label="违规拦截量" :value="reportData.blockCount" helper="BLOCK 记录" icon="gpp_bad" accent="rose" />
              <MetricCard label="人工复核量" :value="reportData.reviewCount" helper="REVIEW 记录" icon="playlist_add_check" accent="amber" />
              <MetricCard
                label="违规率"
                :value="`${(reportData.violationRate * 100).toFixed(2)}%`"
                helper="拦截 / 总量"
                icon="monitoring"
                accent="cyan"
              />
            </div>

            <div class="grid gap-4 xl:grid-cols-[1.45fr_0.9fr]">
              <div class="rounded-3xl border border-sky-400/16 bg-sky-400/[0.07] p-5">
                <div class="flex items-center gap-2">
                  <span class="material-symbols-outlined text-sky-200">auto_awesome</span>
                  <span class="font-display text-lg font-semibold text-slate-50">AI 智能总结</span>
                </div>
                <div class="mt-4 min-h-[120px] text-sm leading-7 text-slate-200">
                  {{ typedSummary }}<span v-if="typing" class="animate-pulse">_</span>
                </div>
              </div>

              <div class="rounded-3xl border border-white/10 bg-slate-900/70 p-5">
                <div class="flex items-center justify-between gap-3">
                  <div>
                    <h3 class="ss-section-title">高频违规关键词</h3>
                    <p class="ss-section-copy">按出现频次展示前 10 个风险词。</p>
                  </div>
                  <span class="ss-chip ss-chip--accent">Top 10</span>
                </div>
                <div class="mt-4 flex flex-wrap gap-2">
                  <el-tag
                    v-for="(kw, idx) in reportData.topKeywords.slice(0, 10)"
                    :key="idx"
                    :type="idx < 3 ? 'danger' : 'warning'"
                    size="small"
                    effect="dark"
                    class="!border-none"
                  >
                    {{ kw.word }} ({{ kw.count }})
                  </el-tag>
                </div>
              </div>
            </div>

            <div class="grid gap-4 xl:grid-cols-[1.25fr_0.95fr]">
              <div class="rounded-3xl border border-white/10 bg-slate-900/70 p-5">
                <div class="mb-4">
                  <h3 class="ss-section-title">24 小时风险趋势</h3>
                  <p class="ss-section-copy">帮助你快速定位风险高峰出现的时间段。</p>
                </div>
                <div ref="hourlyChartRef" class="h-72"></div>
              </div>

              <div class="rounded-3xl border border-white/10 bg-slate-900/70 p-5">
                <div class="mb-4">
                  <h3 class="ss-section-title">典型严重案例</h3>
                  <p class="ss-section-copy">选取高风险 BLOCK 样本，方便日报里直接引用。</p>
                </div>
                <div
                  v-if="reportData.typicalCases && reportData.typicalCases.length > 0"
                  class="ss-scrollbar flex max-h-72 flex-col gap-3 overflow-auto pr-1"
                >
                  <div
                    v-for="c in reportData.typicalCases"
                    :key="c.id"
                    class="rounded-2xl border border-rose-400/14 bg-rose-400/[0.04] p-4"
                  >
                    <div class="mb-2 flex items-center justify-between gap-3">
                      <span class="rounded-full border border-rose-400/16 bg-rose-400/10 px-2.5 py-1 text-xs text-rose-100">
                        Score {{ c.score }}
                      </span>
                      <span class="text-xs text-slate-400">{{ c.reasonTag }}</span>
                    </div>
                    <div class="text-sm leading-6 text-slate-200">{{ c.content }}</div>
                  </div>
                </div>
                <div v-else class="rounded-2xl border border-dashed border-white/10 px-4 py-8 text-center text-sm text-slate-500">
                  暂无高风险严重案例
                </div>
              </div>
            </div>
          </div>
        </SurfacePanel>

        <SurfacePanel v-else-if="!loading" class="flex h-full min-h-[620px] w-full flex-1 items-center justify-center">
          <div class="text-center">
            <div class="mx-auto flex h-16 w-16 items-center justify-center rounded-full border border-white/10 bg-white/[0.03]">
              <span class="material-symbols-outlined text-3xl text-slate-500">event_busy</span>
            </div>
            <p class="mt-5 font-display text-xl text-slate-200">所选日期暂无战报数据</p>
            <p class="mt-2 text-sm text-slate-500">请选择今天或更早的日期。</p>
          </div>
        </SurfacePanel>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { getDailyReport } from '../api/report.js'
import * as echarts from 'echarts'
import html2pdf from 'html2pdf.js'
import MetricCard from '../components/ui/MetricCard.vue'
import PageIntro from '../components/ui/PageIntro.vue'
import SurfacePanel from '../components/ui/SurfacePanel.vue'

const selectedDate = ref(new Date())
const loading = ref(false)
const reportData = ref(null)
const exporting = ref(false)

const dateString = computed(() => {
  const d = selectedDate.value
  if (!d) return ''
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
})

const typedSummary = ref('')
const typing = ref(false)
let typingInterval = null

const hourlyChartRef = ref(null)
let myChart = null

const dateShortcuts = [
  {
    text: '今天',
    value: () => todayAtStart()
  },
  {
    text: '昨天',
    value: () => shiftFromToday(-1)
  },
  {
    text: '3 天前',
    value: () => shiftFromToday(-3)
  },
  {
    text: '7 天前',
    value: () => shiftFromToday(-7)
  }
]

const recentDates = computed(() =>
  Array.from({ length: 14 }, (_, index) => {
    const date = shiftFromToday(-index)
    return {
      key: formatDateKey(date),
      date,
      label: index === 0 ? '今天' : index === 1 ? '昨天' : formatDateLabel(date),
      subLabel: formatDateKey(date)
    }
  })
)

const isTodaySelected = computed(() => isSameDay(selectedDate.value, todayAtStart()))

function todayAtStart() {
  const date = new Date()
  date.setHours(0, 0, 0, 0)
  return date
}

function shiftFromToday(offset) {
  const base = todayAtStart()
  base.setDate(base.getDate() + offset)
  return base
}

function formatDateKey(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

function formatDateLabel(date) {
  const week = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
  return `${date.getMonth() + 1}月${date.getDate()}日 ${week[date.getDay()]}`
}

function isSameDay(a, b) {
  if (!a || !b) return false
  return formatDateKey(a) === formatDateKey(b)
}

function shiftDate(days) {
  const base = new Date(selectedDate.value)
  base.setDate(base.getDate() + days)
  if (base.getTime() > todayAtStart().getTime()) {
    selectedDate.value = todayAtStart()
    return
  }
  selectedDate.value = base
}

function jumpToToday() {
  selectedDate.value = todayAtStart()
}

function disableFutureDate(date) {
  return date.getTime() > todayAtStart().getTime()
}

const fetchReport = async () => {
  if (!dateString.value) return
  loading.value = true
  reportData.value = null
  typedSummary.value = ''
  clearInterval(typingInterval)

  try {
    const res = await getDailyReport(dateString.value)
    if (res && res.code === 200 && res.data) {
      reportData.value = res.data
      startTypingEffect(res.data.aiSummary)
      nextTick(() => {
        renderChart(res.data.hourlyBuckets)
      })
    }
  } catch (error) {
    console.error('加载战报失败', error)
  } finally {
    loading.value = false
  }
}

watch(selectedDate, () => {
  fetchReport()
})

function startTypingEffect(text) {
  if (!text) return
  typing.value = true
  let i = 0
  typingInterval = setInterval(() => {
    if (i < text.length) {
      typedSummary.value += text.charAt(i)
      i += 1
    } else {
      clearInterval(typingInterval)
      typing.value = false
    }
  }, 30)
}

function renderChart(data) {
  if (!hourlyChartRef.value) return
  if (myChart) myChart.dispose()
  myChart = echarts.init(hourlyChartRef.value)

  myChart.setOption({
    backgroundColor: 'transparent',
    tooltip: {
      trigger: 'axis',
      backgroundColor: 'rgba(15,23,42,0.92)',
      borderColor: '#334155',
      textStyle: { color: '#e2e8f0' }
    },
    grid: { left: 28, right: 18, top: 18, bottom: 28, containLabel: true },
    xAxis: {
      type: 'category',
      data: Array.from({ length: 24 }, (_, i) => `${i}时`),
      axisLine: { lineStyle: { color: '#334155' } },
      axisLabel: { color: '#94a3b8' }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: '#1e293b' } },
      axisLabel: { color: '#94a3b8' }
    },
    series: [
      {
        name: '消息量',
        type: 'bar',
        barWidth: '56%',
        data: data || [],
        itemStyle: {
          borderRadius: [8, 8, 0, 0],
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#5EE6FF' },
            { offset: 1, color: '#3B82F6' }
          ])
        }
      }
    ]
  })
}

function exportPdf() {
  const element = document.getElementById('pdf-report-content')
  if (!element) return

  exporting.value = true
  if (typing.value && reportData.value?.aiSummary) {
    clearInterval(typingInterval)
    typedSummary.value = reportData.value.aiSummary
    typing.value = false
  }

  const opt = {
    margin: 10,
    filename: `星盾日报_${dateString.value}.pdf`,
    image: { type: 'jpeg', quality: 0.98 },
    html2canvas: { scale: 2, useCORS: true, backgroundColor: '#0f172a' },
    jsPDF: { unit: 'mm', format: 'a4', orientation: 'portrait' }
  }

  html2pdf().set(opt).from(element).save().finally(() => {
    exporting.value = false
  })
}

function resizeChart() {
  if (myChart) myChart.resize()
}

onMounted(() => {
  fetchReport()
  window.addEventListener('resize', resizeChart)
})

onUnmounted(() => {
  clearInterval(typingInterval)
  window.removeEventListener('resize', resizeChart)
  if (myChart) {
    myChart.dispose()
    myChart = null
  }
})
</script>

<style scoped>
:deep(.ss-report-loading > .el-loading-mask) {
  border-radius: 28px;
  background: rgba(2, 6, 23, 0.6);
  backdrop-filter: blur(10px);
}

:deep(.ss-report-loading .el-loading-spinner .path) {
  stroke: #22d3ee;
}

:deep(.ss-report-loading .el-loading-spinner .el-loading-text) {
  color: #cbd5e1;
}
</style>
