<template>
  <div class="ss-page">
    <!-- 页面头部 -->
    <PageIntro
      eyebrow="Aether Command · Crawl Console"
      title="爬取控制台"
      description="提交 B 站采集任务，实时查看抓取与推送进度，支持任务终止与重跑。"
    >
      <template #meta>
        <div class="grid min-w-[260px] grid-cols-2 gap-3">
          <MetricCard
            label="活跃任务"
            :value="activeCount"
            helper="正在运行"
            icon="download"
            accent="cyan"
          />
          <MetricCard
            label="今日完成"
            :value="todayFinished"
            helper="成功入库"
            icon="check_circle"
            accent="emerald"
          />
        </div>
      </template>
    </PageIntro>

    <!-- 主体：三部分布局 -->
    <div class="grid items-stretch gap-6">
      <!-- 【顶部】控制面板 -->
      <SurfacePanel tone="muted" class="h-fit">
        <div class="mb-5 flex items-start justify-between gap-3">
          <div>
            <h2 class="ss-section-title">新建爬取任务</h2>
            <p class="ss-section-copy">配置采集目标与速率限制。</p>
          </div>
          <span class="ss-chip ss-chip--accent">{{ platformLabel }}</span>
        </div>

        <el-form label-position="top" class="space-y-5">
          <!-- 任务类型 -->
          <el-form-item label="任务类型">
            <el-select v-model="form.type" class="w-full">
              <el-option label="视频评论" value="video" />
              <el-option label="直播间弹幕（实时）" value="live" />
              <el-option label="第五人格微博数据集" value="weibo" />
            </el-select>
          </el-form-item>

          <el-form-item
            v-if="form.type !== 'weibo'"
            :label="form.type === 'live' ? '直播间（每行一个房间号或URL）' : '目标列表（每行一个 BV 号）'"
          >
            <el-input
              v-model="form.targets"
              type="textarea"
              :rows="4"
              :placeholder="form.type === 'live' ? 'https://live.bilibili.com/12345\n67890' : 'BV1CSJxzCENA\nBV1xx123456'"
              class="!rounded-2xl"
            />
          </el-form-item>

          <el-form-item v-if="form.type === 'video' || form.type === 'live'" label="B 站 Cookie（推荐）">
            <el-input
              v-model="form.cookie"
              type="textarea"
              :rows="3"
              placeholder="从浏览器复制 Cookie，需包含 SESSDATA；不填则仅能抓取少量精选评论"
              class="!rounded-2xl"
            />
            <p class="mt-2 text-xs leading-relaxed text-slate-400">
              打开 bilibili.com → F12 → 网络/应用 → Cookie，复制整段或至少 SESSDATA=...。
              仅保存在本机浏览器，不会写入数据库。
            </p>
          </el-form-item>

          <el-form-item v-else label="数据源">
            <p class="text-sm text-slate-400 leading-relaxed">
              自动从 HuggingFace 下载 IdentityV-weibo（约 3.1 万条玩家评论），
              以 platform=WEIBO 写入数据库。
            </p>
          </el-form-item>

          <!-- 目标条数 -->
          <el-form-item label="目标条数">
            <el-input-number
              v-model="form.targetCount"
              :min="1"
              :max="100000"
              class="w-full"
            />
          </el-form-item>

          <!-- RPS 滑块 -->
          <el-form-item label="推送速率（RPS）">
            <el-slider
              v-model="form.rps"
              :min="1"
              :max="300"
              show-input
            />
          </el-form-item>

          <!-- 提交按钮 -->
          <el-button
            type="primary"
            class="ss-button-primary !border-none !w-full !py-4 !text-base !font-semibold"
            :loading="submitting"
            @click="submitTask"
          >
            开始爬取
          </el-button>
        </el-form>
      </SurfacePanel>

      <!-- 【中部】当前活跃任务卡片 -->
      <SurfacePanel v-if="activeTask" class="border-sky-400/30 bg-sky-400/[0.05]">
        <div class="mb-5 flex items-start justify-between gap-3">
          <div>
            <h2 class="ss-section-title">当前任务进度</h2>
            <p class="ss-section-copy">实时显示抓取与推送状态。</p>
          </div>
          <span class="ss-chip ss-chip--accent animate-pulse">LIVE</span>
        </div>

        <!-- 任务信息 -->
        <div class="mb-6 rounded-3xl border border-sky-400/20 bg-slate-900/70 p-5">
          <div class="flex items-center justify-between mb-4">
            <div class="flex items-center gap-3">
              <span class="material-symbols-outlined text-sky-200">smart_toy</span>
              <div>
                <p class="font-display text-lg font-semibold text-slate-50">
                  任务 #{{ activeTask.id }}
                </p>
                <p class="text-sm text-slate-400">
                  {{ taskTypeText(activeTask.type) }}
                </p>
              </div>
            </div>
            <span class="ss-chip" :class="statusClass(activeTask.status)">
              {{ statusText(activeTask.status) }}
            </span>
          </div>

          <!-- 进度条 -->
          <div class="mb-4">
            <div class="mb-2 flex items-center justify-between text-xs">
              <span class="text-slate-400">抓取进度</span>
              <span class="text-sky-200 font-medium">
                {{ progressPercent(activeTask) }}%
              </span>
            </div>
            <el-progress
              :percentage="progressPercent(activeTask)"
              :stroke-width="12"
              :show-text="false"
              class="!rounded-full"
              :color="progressColor(activeTask.status)"
            />
          </div>

          <!-- 实时数据 -->
          <div class="grid grid-cols-3 gap-4">
            <div class="rounded-2xl border border-white/10 bg-slate-900/70 p-4 text-center">
              <p class="text-2xl font-bold text-slate-50">{{ activeTask.fetchedCount }}</p>
              <p class="text-xs text-slate-400">已抓取</p>
            </div>
            <div class="rounded-2xl border border-white/10 bg-slate-900/70 p-4 text-center">
              <p class="text-2xl font-bold text-slate-50">{{ activeTask.pushedCount }}</p>
              <p class="text-xs text-slate-400">已推送</p>
            </div>
            <div class="rounded-2xl border border-white/10 bg-slate-900/70 p-4 text-center">
              <p class="text-2xl font-bold text-slate-50">{{ currentRate }}</p>
              <p class="text-xs text-slate-400">速率 (条/秒)</p>
            </div>
          </div>

          <!-- 终止按钮 -->
          <div class="mt-5 flex justify-end">
            <el-button
              v-if="activeTask.status === 'running' || activeTask.status === 'pending'"
              type="danger"
              size="small"
              class="!rounded-xl !border-none !px-4 !py-2 !font-medium"
              @click="stopTask(activeTask.id)"
            >
              终止任务
            </el-button>
          </div>
        </div>
      </SurfacePanel>

      <!-- 【下方】最近任务表格 -->
      <SurfacePanel class="min-h-[420px]">
        <div class="mb-5 flex items-start justify-between gap-3">
          <div>
            <h2 class="ss-section-title">最近任务</h2>
            <p class="ss-section-copy">查看历史任务记录。</p>
          </div>
          <span class="ss-chip ss-chip--accent">RECENT 20</span>
        </div>

        <div class="ss-scrollbar overflow-auto pr-1" style="max-height: 440px">
          <div
            v-for="task in tasks"
            :key="task.id"
            class="mb-3 rounded-2xl border border-white/10 bg-slate-900/70 p-4 transition hover:border-sky-400/20"
          >
            <div class="flex items-center justify-between gap-3">
              <div class="flex items-center gap-3 min-w-0">
                <span class="material-symbols-outlined text-sky-200">history</span>
                <div class="min-w-0">
                  <p class="font-medium text-slate-50 truncate">
                    任务 #{{ task.id }}
                  </p>
                  <p class="text-xs text-slate-400">
                    {{ formatTime(task.createTime) }}
                  </p>
                </div>
              </div>

              <div class="flex items-center gap-3">
                <span class="text-sm text-slate-400">
                  {{ task.fetchedCount }}/{{ task.targetCount }}
                </span>
                <span class="ss-chip" :class="statusClass(task.status)">
                  {{ statusText(task.status) }}
                </span>
                <div class="flex gap-2">
                  <el-button
                    size="small"
                    class="!rounded-xl !border-none !px-3 !py-1 !font-medium"
                    @click="viewTask(task)"
                  >
                    查看
                  </el-button>
                  <el-button
                    v-if="task.status === 'failed'"
                    type="primary"
                    size="small"
                    class="!rounded-xl !border-none !px-3 !py-1 !font-medium"
                    @click="retryTask(task.id)"
                  >
                    重跑
                  </el-button>
                  <el-button
                    v-if="task.status === 'finished'"
                    type="success"
                    size="small"
                    class="!rounded-xl !border-none !px-3 !py-1 !font-medium"
                    @click="goToDashboard"
                  >
                    大屏
                  </el-button>
                </div>
              </div>
            </div>
          </div>
        </div>
      </SurfacePanel>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fetchCrawlTasks, stopCrawlTask, submitCrawlTask } from '../api/crawl'
import MetricCard from '../components/ui/MetricCard.vue'
import PageIntro from '../components/ui/PageIntro.vue'
import SurfacePanel from '../components/ui/SurfacePanel.vue'

const emit = defineEmits(['navigate'])

const COOKIE_STORAGE_KEY = 'starshield_bili_cookie'

// 表单数据
const form = ref({
  type: 'video',
  targets: '',
  targetCount: 100,
  rps: 20,
  cookie: typeof sessionStorage !== 'undefined' ? sessionStorage.getItem(COOKIE_STORAGE_KEY) || '' : ''
})

// 任务列表
const tasks = ref([])
const submitting = ref(false)
let pollTimer = null
let rateTimer = null
let lastFetchedCount = 0

// 计算属性
const activeTask = computed(() => {
  if (tasks.value.length === 0) return null
  // 显示最新的任务（不管状态）
  return [...tasks.value].sort((a, b) => 
    new Date(b.createTime) - new Date(a.createTime)
  )[0]
})

const activeCount = computed(() => 
  tasks.value.filter(t => t.status === 'running' || t.status === 'pending').length
)

const todayFinished = computed(() => 
  tasks.value.filter(t => t.status === 'finished').length
)

// 实时速率计算
const currentRate = ref(0)

const platformLabel = computed(() => {
  const map = { video: 'BILIBILI', live: 'BILIBILI_LIVE', weibo: 'WEIBO' }
  return map[form.value.type] || 'BILIBILI'
})

const taskTypeText = (type) => {
  const map = {
    video: '视频评论',
    live: '直播间弹幕（实时 WebSocket）',
    weibo: '第五人格微博数据集'
  }
  return map[type] || type
}

// 提交任务
const submitTask = async () => {
  let targets = form.value.targets
    .split('\n')
    .map(v => v.trim())
    .filter(Boolean)

  if (form.value.type === 'weibo') {
    targets = ['IdentityV-weibo']
  } else if (targets.length === 0) {
    ElMessage.warning(form.value.type === 'live' ? '请输入至少一个直播间' : '请输入至少一个 BV 号')
    return
  }

  const cookie = form.value.cookie?.trim()
  if ((form.value.type === 'video' || form.value.type === 'live') && !cookie) {
    try {
      await ElMessageBox.confirm(
        '未填写 B 站 Cookie 时，每个视频通常只能抓到少量精选评论。是否仍继续？',
        'Cookie 未填写',
        { confirmButtonText: '继续', cancelButtonText: '返回填写', type: 'warning' }
      )
    } catch {
      return
    }
  }

  submitting.value = true
  try {
    const payload = {
      type: form.value.type,
      targets,
      targetCount: form.value.targetCount,
      rps: form.value.rps
    }
    if (cookie) {
      payload.cookie = cookie
      sessionStorage.setItem(COOKIE_STORAGE_KEY, cookie)
    }

    const res = await submitCrawlTask(payload)

    if (res.code === 200) {
      const taskId = res.data
      ElMessage.success(`任务已提交，ID：${taskId}`)
      form.value.targets = ''

      // ✅ 立即创建任务卡片（乐观更新）
      const newTask = {
        id: taskId,
        type: form.value.type,
        targetsJson: JSON.stringify(targets),
        targetCount: form.value.targetCount,
        fetchedCount: 0,
        pushedCount: 0,
        status: 'pending',
        createTime: new Date().toISOString(),
        finishTime: null,
        errorMsg: null
      }
      tasks.value.unshift(newTask)

      startPolling()
      startRateCalculation()
    } else {
      ElMessage.error(res.message || '提交失败')
    }
  } catch (error) {
    ElMessage.error('提交任务失败')
    console.error(error)
  } finally {
    submitting.value = false
  }
}

// 获取任务列表
const fetchTasks = async () => {
  try {
    const res = await fetchCrawlTasks(20)
    if (res.code === 200) {
      const newTasks = res.data || []
      
      // 更新现有任务
      newTasks.forEach(backendTask => {
        const index = tasks.value.findIndex(t => t.id === backendTask.id)
        if (index !== -1) {
          tasks.value[index] = backendTask
        } else {
          tasks.value.unshift(backendTask)
        }
      })

      // ✅ 检测任务完成并弹出 Toast
      const justFinished = newTasks.find(t => 
        t.status === 'finished' && 
        tasks.value.find(ot => ot.id === t.id && ot.status !== 'finished')
      )

      if (justFinished) {
        ElMessage.success({
          message: `任务 #${justFinished.id} 已完成！`,
          duration: 5000,
          showClose: true
        })
      }
    }
  } catch (error) {
    console.error('获取任务列表失败', error)
  }
}

// 查看任务详情
const viewTask = (task) => {
  ElMessageBox.alert(
    `
    <div class="space-y-2">
      <p><strong>任务 ID：</strong>${task.id}</p>
      <p><strong>类型：</strong>${taskTypeText(task.type)}</p>
      <p><strong>目标数量：</strong>${task.targetCount} 条</p>
      <p><strong>已抓取：</strong>${task.fetchedCount} 条</p>
      <p><strong>已推送：</strong>${task.pushedCount} 条</p>
      <p><strong>状态：</strong>${statusText(task.status)}</p>
      <p><strong>创建时间：</strong>${formatTime(task.createTime)}</p>
      ${task.finishTime ? `<p><strong>完成时间：</strong>${formatTime(task.finishTime)}</p>` : ''}
      ${task.errorMsg ? `<p class="text-red-400"><strong>错误信息：</strong>${task.errorMsg}</p>` : ''}
    </div>
    `,
    '任务详情',
    {
      dangerouslyUseHTMLString: true,
      confirmButtonText: '关闭',
      customClass: '!bg-slate-900 !border !border-white/10'
    }
  )
}

// 终止任务
const stopTask = async (id) => {
  try {
    await ElMessageBox.confirm(
      '确定要终止该任务吗？已抓取的数据不会丢失。',
      '终止确认',
      {
        confirmButtonText: '确定终止',
        cancelButtonText: '取消',
        type: 'warning',
      }
    )

    await stopCrawlTask(id)
    ElMessage.success('任务已终止')
    fetchTasks()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('终止任务失败')
    }
  }
}

// 重跑任务
const retryTask = async (id) => {
  try {
    await ElMessageBox.confirm(
      '确定要重跑该任务吗？这将重新开始抓取。',
      '重跑确认',
      {
        confirmButtonText: '确定重跑',
        cancelButtonText: '取消',
        type: 'warning',
      }
    )

    const oldTask = tasks.value.find(t => t.id === id)
    if (oldTask) {
      form.value.targets = JSON.parse(oldTask.targetsJson).join('\n')
      form.value.targetCount = oldTask.targetCount
      form.value.type = oldTask.type
      await submitTask()
    }
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('重跑任务失败')
    }
  }
}

// 跳转到数据大屏（SPA 内切换 tab）
const goToDashboard = () => {
  emit('navigate', 'dashboard')
}

// 轮询任务状态
const startPolling = () => {
  if (pollTimer) clearInterval(pollTimer)
  
  pollTimer = setInterval(() => {
    fetchTasks()
  }, 2000)
}

// 计算实时速率
const startRateCalculation = () => {
  if (rateTimer) clearInterval(rateTimer)
  
  rateTimer = setInterval(() => {
    const task = activeTask.value
    if (task && task.status === 'running') {
      const current = task.fetchedCount
      currentRate.value = Math.round((current - lastFetchedCount) / 2)
      lastFetchedCount = current
    }
  }, 2000)
}

// 工具函数
const statusText = (status) => {
  const map = {
    pending: '等待中',
    running: '运行中',
    finished: '已完成',
    failed: '失败'
  }
  return map[status] || status
}

const statusClass = (status) => {
  const map = {
    pending: 'ss-chip--accent',
    running: 'ss-chip--accent',
    finished: 'ss-chip--success',
    failed: 'ss-chip--danger'
  }
  return map[status] || 'ss-chip--accent'
}

const progressColor = (status) => {
  const map = {
    pending: '#64748b',
    running: '#0ea5e9',
    finished: '#10b981',
    failed: '#ef4444'
  }
  return map[status] || '#64748b'
}

const progressPercent = (task) => {
  if (!task.targetCount) return 0
  return Math.min(
    Math.round((task.fetchedCount / task.targetCount) * 100),
    100
  )
}

const formatTime = (timeStr) => {
  if (!timeStr) return '--'
  const date = new Date(timeStr)
  return `${date.getMonth()+1}/${date.getDate()} ${date.getHours().toString().padStart(2, '0')}:${date.getMinutes().toString().padStart(2, '0')}`
}

// 生命周期
onMounted(() => {
  fetchTasks()
  startPolling()
  startRateCalculation()
})

onUnmounted(() => {
  if (pollTimer) {
    clearInterval(pollTimer)
  }
  if (rateTimer) {
    clearInterval(rateTimer)
  }
})
</script>

<style scoped>
/* 简洁样式，不使用 @apply */
:deep(.el-input__wrapper),
:deep(.el-textarea__inner),
:deep(.el-select__wrapper) {
  border-radius: 16px !important;
  border: 1px solid rgba(255, 255, 255, 0.1) !important;
  background: rgba(255, 255, 255, 0.03) !important;
  padding: 12px 16px !important;
  color: #eaf2ff !important;
  box-shadow: none !important;
}

:deep(.el-input-number) {
  width: 100%;
}

:deep(.el-slider__runway) {
  height: 8px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.1);
}

:deep(.el-slider__bar) {
  height: 8px;
  border-radius: 4px;
  background: linear-gradient(90deg, #5ee6ff, #3b82f6);
}

:deep(.el-slider__button) {
  width: 20px;
  height: 20px;
  border: 2px solid #3b82f6;
  background: #0a1020;
  box-shadow: 0 0 8px rgba(59, 130, 246, 0.5);
}

:deep(.el-progress-bar__outer) {
  border-radius: 9999px;
  background: rgba(255, 255, 255, 0.1);
}

:deep(.el-progress-bar__inner) {
  border-radius: 9999px;
}

:deep(.el-button--primary) {
  background: #3b82f6 !important;
  border: none !important;
}

:deep(.el-button--danger) {
  background: #ef4444 !important;
  border: none !important;
}

:deep(.el-button--success) {
  background: #10b981 !important;
  border: none !important;
}

/* 滚动条样式 */
.ss-scrollbar {
  scrollbar-width: thin;
  scrollbar-color: rgba(148, 163, 184, 0.3) transparent;
}

.ss-scrollbar::-webkit-scrollbar {
  width: 6px;
}

.ss-scrollbar::-webkit-scrollbar-track {
  background: transparent;
}

.ss-scrollbar::-webkit-scrollbar-thumb {
  background-color: rgba(148, 163, 184, 0.3);
  border-radius: 3px;
}

/* 动画 */
.animate-pulse {
  animation: pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite;
}

@keyframes pulse {
  0%, 100% {
    opacity: 1;
  }
  50% {
    opacity: .5;
  }
}
</style>