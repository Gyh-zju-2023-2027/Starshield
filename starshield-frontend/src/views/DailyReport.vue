<template>
  <div class="p-6 text-slate-300 min-h-screen flex gap-6">
    <!-- 左侧日历选择 -->
    <div class="w-1/3 max-w-sm shrink-0">
      <el-card class="box-card h-full" shadow="never">
        <template #header>
          <div class="card-header font-bold text-lg">选择日期</div>
        </template>
        <el-calendar v-model="selectedDate" />
      </el-card>
    </div>

    <!-- 右侧报告内容区 -->
    <div class="flex-1 overflow-hidden" v-loading="loading">
      <!-- 只有在不加载且有数据时才显示可导出区域 -->
      <div v-if="reportData" class="bg-gray-800 rounded-xl p-8 relative flex flex-col gap-6" id="pdf-report-content">
        <!-- 头部栏 -->
        <div class="flex justify-between items-center bg-gray-800 pb-4 border-b border-gray-700">
          <div>
            <h2 class="text-2xl font-bold text-blue-400">星盾每日治理战报</h2>
            <p class="text-sm text-gray-400 mt-2">{{ dateString }} 数据报告</p>
          </div>
          <el-button type="primary" class="!bg-blue-600 !border-none" @click="exportPdf" 
                     data-html2canvas-ignore="true" :loading="exporting">
            导出 PDF
          </el-button>
        </div>

        <!-- 核心 KPI (4张卡片) -->
        <div class="grid grid-cols-4 gap-4 mt-2">
          <div class="bg-gray-700/50 p-4 rounded-lg flex flex-col items-center justify-center">
            <div class="text-sm text-gray-400 mb-1">总消息量</div>
            <div class="text-xl font-bold text-slate-100">{{ reportData.totalCount }}</div>
          </div>
          <div class="bg-gray-700/50 p-4 rounded-lg flex flex-col items-center justify-center">
            <div class="text-sm text-red-400 mb-1">违规拦截量</div>
            <div class="text-xl font-bold text-red-500">{{ reportData.blockCount }}</div>
          </div>
          <div class="bg-gray-700/50 p-4 rounded-lg flex flex-col items-center justify-center">
            <div class="text-sm text-yellow-500 mb-1">人工复核量</div>
            <div class="text-xl font-bold text-yellow-400">{{ reportData.reviewCount }}</div>
          </div>
          <div class="bg-gray-700/50 p-4 rounded-lg flex flex-col items-center justify-center">
            <div class="text-sm text-blue-400 mb-1">违规率</div>
            <div class="text-xl font-bold text-blue-300">{{ (reportData.violationRate * 100).toFixed(2) }}%</div>
          </div>
        </div>

        <!-- 智能总结 (打字机效果) -->
        <div class="bg-blue-900/20 border border-blue-500/30 rounded-lg p-5">
          <div class="flex items-center gap-2 mb-3">
            <i class="el-icon-cpu text-blue-400"></i>
            <span class="font-bold text-blue-300">AI 智能总结</span>
          </div>
          <div class="text-sm text-slate-300 leading-relaxed min-h-[4rem]">
            {{ typedSummary }}<span v-if="typing" class="animate-pulse">_</span>
          </div>
        </div>

        <!-- 中间图表行 -->
        <div class="flex gap-4 h-64">
           <!-- 24小时违规分布 -->
           <div class="flex-1 bg-gray-700/30 rounded-lg p-4" ref="hourlyChartRef"></div>
           <!-- 高频违规关键词 -->
           <div class="w-1/3 bg-gray-700/30 rounded-lg p-4 flex flex-col">
             <div class="text-sm text-gray-400 mb-3 font-bold">高频违规关键词 (TOP)</div>
             <div class="flex-1 overflow-auto flex flex-wrap gap-2 content-start">
               <el-tag v-for="(kw, idx) in reportData.topKeywords.slice(0, 10)" :key="idx" 
                       :type="idx < 3 ? 'danger' : 'warning'" size="small" effect="dark" class="border-none">
                 {{ kw.word }} ({{ kw.count }})
               </el-tag>
             </div>
           </div>
        </div>

        <!-- 典型案例 -->
        <div class="bg-gray-700/30 rounded-lg p-4 flex-1 flex flex-col">
          <div class="text-sm text-gray-400 mb-3 font-bold">典型严重违规案例选录 (<span class="text-red-400">BLOCK</span>)</div>
          <div v-if="reportData.typicalCases && reportData.typicalCases.length > 0" class="flex flex-col gap-3">
             <div v-for="c in reportData.typicalCases" :key="c.id" class="bg-gray-800 p-3 rounded border-l-4 border-red-500 hover:bg-gray-700 transition">
               <div class="flex items-center justify-between mb-2">
                 <span class="text-xs bg-red-500/20 text-red-400 px-2 py-1 rounded">Score: {{ c.score }}</span>
                 <span class="text-xs text-gray-400 bg-gray-700 px-2 py-1 rounded">{{ c.reasonTag }}</span>
               </div>
               <div class="text-sm text-slate-200">{{ c.content }}</div>
             </div>
          </div>
          <div v-else class="text-xs text-gray-500 italic mt-2">暂无高风险严重案例</div>
        </div>

      </div>

      <!-- 空状态 -->
      <div v-else-if="!loading" class="flex flex-col items-center justify-center h-full text-gray-500">
        <i class="el-icon-document text-6xl mb-4 opacity-20"></i>
        <p>所选日期暂无治理报告数据</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { getDailyReport } from '../api/report.js'
import * as echarts from 'echarts'
import html2pdf from 'html2pdf.js'

const selectedDate = ref(new Date())
const loading = ref(false)
const reportData = ref(null)
const exporting = ref(false)

const dateString = computed(() => {
  const d = selectedDate.value
  if (!d) return ''
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
})

// 打字机状态
const typedSummary = ref('')
const typing = ref(false)
let typingInterval = null

// 图表实例
const hourlyChartRef = ref(null)
let myChart = null

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
    console.error("加载战报失败", error)
  } finally {
    loading.value = false
  }
}

watch(selectedDate, () => {
  fetchReport()
})

const startTypingEffect = (text) => {
  if (!text) return
  typing.value = true
  let i = 0
  typingInterval = setInterval(() => {
    if (i < text.length) {
      typedSummary.value += text.charAt(i)
      i++
    } else {
      clearInterval(typingInterval)
      typing.value = false
    }
  }, 30) // 打字速度
}

const renderChart = (data) => {
  if (!hourlyChartRef.value) return
  if (myChart) myChart.dispose()
  myChart = echarts.init(hourlyChartRef.value)
  
  const option = {
    title: {
      text: '全天违规风险量分布',
      textStyle: { color: '#94a3b8', fontSize: 12, fontWeight: 'bold' }
    },
    tooltip: {
      trigger: 'axis'
    },
    grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
    xAxis: {
      type: 'category',
      data: Array.from({length: 24}, (_, i) => `${i}时`),
      axisLabel: { color: '#64748b' }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: '#334155' } },
      axisLabel: { color: '#64748b' }
    },
    series: [
      {
        name: '消息量',
        type: 'bar',
        barWidth: '60%',
        data: data || [],
        itemStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#3b82f6' },
            { offset: 1, color: '#1d4ed8' }
          ])
        }
      }
    ]
  }
  myChart.setOption(option)
}

const exportPdf = () => {
  const element = document.getElementById('pdf-report-content')
  if (!element) return
  
  exporting.value = true
  // 由于 html2pdf 会克隆节点，图表可能需要配置。但 html2pdf 提供默认支持
  // 暂时先强制把打字机动画结果全部展示完成再导出，避免截断
  if (typing.value && reportData.value?.aiSummary) {
    clearInterval(typingInterval)
    typedSummary.value = reportData.value.aiSummary
    typing.value = false
  }

  const opt = {
    margin:       10,
    filename:     `星盾日报_${dateString.value}.pdf`,
    image:        { type: 'jpeg', quality: 0.98 },
    html2canvas:  { scale: 2, useCORS: true, backgroundColor: '#1e293b' },
    jsPDF:        { unit: 'mm', format: 'a4', orientation: 'portrait' }
  }

  html2pdf().set(opt).from(element).save().finally(() => {
    exporting.value = false
  })
}

onMounted(() => {
  // 默认加载今天的数据
  fetchReport()
})

// 监听窗口尺寸改变调整图表
window.addEventListener('resize', () => {
  if (myChart) myChart.resize()
})
</script>

<style scoped>
/* 针对 el-calendar 覆盖它的默认亮色样式使之稍微融入深色主题 */
:deep(.el-calendar) {
  --el-calendar-border-color: #334155;
  --el-calendar-bg-color: transparent;
  color: #94a3b8;
  background-color: transparent;
  width: 100%;
}
:deep(.el-calendar-table td.is-selected) {
  background-color: #1e3a8a;
  color: #fff;
}
:deep(.el-calendar__header) {
  border-bottom: 1px solid #334155;
}
:deep(.el-calendar-table td.is-today) {
  color: #60a5fa;
}
:deep(.el-card) {
  background-color: #1e293b;
  border: 1px solid #334155;
  color: #cbd5e1;
}
:deep(.el-card__header) {
  border-bottom: 1px solid #334155;
}
</style>
