<template>
  <div class="shield-wrap">
    <!-- ===== 顶部标题栏 ===== -->
    <header class="shield-header">
      <div class="logo">
        <span class="logo-icon">⬡</span>
        <span class="logo-text">StarShield</span>
        <span class="logo-sub">舆情监控中台</span>
      </div>
      <div class="header-tag">压力测试控制台</div>
    </header>

    <!-- ===== 主内容区 ===== -->
    <main class="shield-main">

      <!-- 参数配置卡片 -->
      <section class="card config-card">
        <h2 class="card-title">发射参数配置</h2>
        <div class="config-grid">
          <div class="config-item">
            <label>并发请求数</label>
            <el-input-number
              v-model="config.total"
              :min="1"
              :max="5000"
              :step="100"
              :disabled="running"
              class="cfg-input"
            />
          </div>
          <div class="config-item">
            <label>批次大小</label>
            <el-input-number
              v-model="config.batchSize"
              :min="1"
              :max="200"
              :step="10"
              :disabled="running"
              class="cfg-input"
            />
          </div>
          <div class="config-item">
            <label>来源平台</label>
            <el-select v-model="config.platform" :disabled="running" class="cfg-input">
              <el-option label="游戏内聊天" value="GAME_INNER" />
              <el-option label="B站弹幕" value="BILIBILI" />
              <el-option label="微博评论" value="WEIBO" />
              <el-option label="抖音直播" value="DOUYIN" />
            </el-select>
          </div>
        </div>
      </section>

      <!-- 发射按钮区 -->
      <section class="card launch-card">
        <button
          class="launch-btn"
          :class="{ firing: running, done: stats.done && !running }"
          :disabled="running"
          @click="startTest"
        >
          <span v-if="!running && !stats.done" class="btn-inner">
            <span class="btn-icon">▶</span>
            模拟发送 {{ config.total }} 条并发日志
          </span>
          <span v-else-if="running" class="btn-inner">
            <span class="spinner"></span>
            正在发射中... {{ stats.success + stats.fail }} / {{ config.total }}
          </span>
          <span v-else class="btn-inner">
            <span class="btn-icon">✓</span>
            测试完成 · 点击重新测试
          </span>
        </button>
      </section>

      <!-- 实时统计面板 -->
      <section class="card stats-card" v-if="stats.done || running">
        <h2 class="card-title">实时统计</h2>
        <div class="stats-grid">
          <div class="stat-item">
            <div class="stat-val success">{{ stats.success }}</div>
            <div class="stat-label">成功</div>
          </div>
          <div class="stat-item">
            <div class="stat-val fail">{{ stats.fail }}</div>
            <div class="stat-label">失败</div>
          </div>
          <div class="stat-item">
            <div class="stat-val total">{{ stats.success + stats.fail }}</div>
            <div class="stat-label">已发送</div>
          </div>
          <div class="stat-item">
            <div class="stat-val duration">{{ stats.duration }}ms</div>
            <div class="stat-label">总耗时</div>
          </div>
          <div class="stat-item">
            <div class="stat-val qps">{{ stats.qps }}</div>
            <div class="stat-label">QPS</div>
          </div>
          <div class="stat-item">
            <div class="stat-val rate"
              :class="{ danger: parseFloat(stats.successRate) < 90 }">
              {{ stats.successRate }}%
            </div>
            <div class="stat-label">成功率</div>
          </div>
        </div>

        <!-- 进度条 -->
        <div class="progress-wrap">
          <div
            class="progress-bar"
            :style="{ width: progressPercent + '%' }"
            :class="{ complete: !running && stats.done }"
          ></div>
        </div>
        <div class="progress-label">{{ progressPercent }}%</div>
      </section>

      <!-- 日志滚动面板 -->
      <section class="card log-card" v-if="logs.length > 0">
        <div class="log-header">
          <h2 class="card-title">请求日志</h2>
          <button class="clear-btn" @click="logs = []">清空</button>
        </div>
        <div class="log-scroll" ref="logContainer">
          <div
            v-for="(entry, i) in logs"
            :key="i"
            class="log-line"
            :class="entry.type"
          >
            <span class="log-time">{{ entry.time }}</span>
            <span class="log-msg">{{ entry.msg }}</span>
          </div>
        </div>
      </section>

    </main>
  </div>
</template>

<script setup>
import { ref, reactive, computed, nextTick } from 'vue'
import { uploadChatMessage } from '../api/chat.js'

// ===================== 响应式数据 =====================

/** 测试配置 */
const config = reactive({
  total: 1000,      // 并发请求总数
  batchSize: 50,    // 每批次并发数（防止浏览器连接数限制）
  platform: 'GAME_INNER'
})

/** 运行状态 */
const running = ref(false)

/** 统计数据 */
const stats = reactive({
  success: 0,
  fail: 0,
  duration: 0,
  qps: 0,
  successRate: '0.00',
  done: false
})

/** 日志列表（最多保留 500 条） */
const logs = ref([])
const logContainer = ref(null)

// ===================== 计算属性 =====================

const progressPercent = computed(() => {
  const total = config.total
  if (total === 0) return 0
  return Math.min(100, Math.round(((stats.success + stats.fail) / total) * 100))
})

// ===================== 工具方法 =====================

/** 生成随机玩家ID */
function randomPlayerId() {
  return 'P' + Math.floor(Math.random() * 9000000 + 1000000)
}

/** 随机发言内容库 */
const contentPool = [
  '这把操作太猛了！', '主播你好厉害！', '求组队一起打boss',
  '这个装备怎么搭配？', '服务器卡不卡', '今天状态不错冲！',
  '坑队友直接开踢', '新版本更新了什么', '这皮肤好看多少钱',
  '大佬带带我吧！', '这局稳了', '为什么突然断线了？'
]

function randomContent() {
  return contentPool[Math.floor(Math.random() * contentPool.length)]
}

/** 追加日志（自动滚动到底部） */
function addLog(type, msg) {
  const now = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  logs.value.push({ type, time: now, msg })
  // 超过 500 条自动裁剪，防止内存溢出
  if (logs.value.length > 500) {
    logs.value.splice(0, logs.value.length - 500)
  }
  nextTick(() => {
    if (logContainer.value) {
      logContainer.value.scrollTop = logContainer.value.scrollHeight
    }
  })
}

// ===================== 核心测试逻辑 =====================

/**
 * 开始并发压力测试
 *
 * 【架构说明】
 * 直接用 Promise.all 发送全部请求会触发浏览器并发限制（约 6 个 TCP 连接/域名），
 * 导致大量请求排队而非真正并发。
 * 此处采用「分批并发」策略：每批 batchSize 个请求真正并发执行，
 * 批次间无延迟，总体效果接近真实高并发场景。
 */
async function startTest() {
  // 重置状态
  running.value = true
  stats.success = 0
  stats.fail = 0
  stats.duration = 0
  stats.qps = 0
  stats.successRate = '0.00'
  stats.done = false
  logs.value = []

  addLog('info', `开始压力测试：共 ${config.total} 条请求，批次大小 ${config.batchSize}`)

  const startTime = Date.now()
  const total = config.total
  const batchSize = config.batchSize

  // 分批执行，每批并发 batchSize 个请求
  for (let i = 0; i < total; i += batchSize) {
    const batchEnd = Math.min(i + batchSize, total)
    const batchPromises = []

    for (let j = i; j < batchEnd; j++) {
      const payload = {
        playerId: randomPlayerId(),
        content: randomContent(),
        platform: config.platform,
        status: 0  // 初始状态：待处理
      }

      // 每个请求独立处理成功/失败，不因单个失败中断整体
      const p = uploadChatMessage(payload)
        .then(res => {
          if (res && res.code === 200) {
            stats.success++
          } else {
            stats.fail++
            addLog('warn', `请求 #${j + 1} 响应异常: ${JSON.stringify(res)}`)
          }
        })
        .catch(err => {
          stats.fail++
          addLog('error', `请求 #${j + 1} 失败: ${err.message}`)
        })

      batchPromises.push(p)
    }

    // 等待当前批次全部完成再进入下一批
    await Promise.all(batchPromises)

    addLog('info', `批次完成：${batchEnd}/${total}，成功 ${stats.success}，失败 ${stats.fail}`)
  }

  // 计算最终统计数据
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

<style scoped>
/* ===================== 全局布局 ===================== */
.shield-wrap {
  min-height: 100vh;
  background: #0b0f1a;
  color: #c9d1e0;
  font-family: 'JetBrains Mono', 'Fira Code', 'Courier New', monospace;
}

/* ===================== 顶部标题栏 ===================== */
.shield-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 40px;
  border-bottom: 1px solid #1e2940;
  background: rgba(11, 15, 26, 0.95);
  backdrop-filter: blur(10px);
  position: sticky;
  top: 0;
  z-index: 100;
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
}

.logo-icon {
  font-size: 26px;
  color: #4af0c8;
  filter: drop-shadow(0 0 8px #4af0c880);
  animation: pulse 3s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { filter: drop-shadow(0 0 6px #4af0c860); }
  50%       { filter: drop-shadow(0 0 16px #4af0c8cc); }
}

.logo-text {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 0.08em;
  color: #e8f0ff;
}

.logo-sub {
  font-size: 12px;
  color: #4a5980;
  letter-spacing: 0.15em;
  text-transform: uppercase;
  padding: 2px 8px;
  border: 1px solid #1e2940;
  border-radius: 4px;
}

.header-tag {
  font-size: 12px;
  color: #4af0c8;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  border: 1px solid #4af0c840;
  padding: 4px 12px;
  border-radius: 4px;
  background: #4af0c808;
}

/* ===================== 主内容区 ===================== */
.shield-main {
  max-width: 960px;
  margin: 0 auto;
  padding: 36px 24px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

/* ===================== 通用卡片 ===================== */
.card {
  background: #111827;
  border: 1px solid #1e2940;
  border-radius: 12px;
  padding: 28px 32px;
  position: relative;
  overflow: hidden;
}

.card::before {
  content: '';
  position: absolute;
  top: 0; left: 0; right: 0;
  height: 2px;
  background: linear-gradient(90deg, #4af0c8, #3b82f6, #8b5cf6);
  opacity: 0.6;
}

.card-title {
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  color: #4a5980;
  margin-bottom: 22px;
}

/* ===================== 配置卡片 ===================== */
.config-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 20px;
}

.config-item {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.config-item label {
  font-size: 12px;
  color: #4a5980;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.cfg-input {
  width: 100%;
}

/* Element Plus 暗色覆盖 */
:deep(.el-input__wrapper),
:deep(.el-input-number .el-input__wrapper) {
  background: #0b0f1a !important;
  box-shadow: 0 0 0 1px #1e2940 inset !important;
}

:deep(.el-input__inner) {
  color: #c9d1e0 !important;
  font-family: inherit !important;
}

:deep(.el-select .el-input__wrapper) {
  background: #0b0f1a !important;
  box-shadow: 0 0 0 1px #1e2940 inset !important;
}

:deep(.el-select-dropdown) {
  background: #111827 !important;
  border-color: #1e2940 !important;
}

:deep(.el-select-dropdown__item) {
  color: #c9d1e0 !important;
}

:deep(.el-select-dropdown__item.hover),
:deep(.el-select-dropdown__item:hover) {
  background: #1e2940 !important;
}

/* ===================== 发射按钮 ===================== */
.launch-card {
  display: flex;
  justify-content: center;
}

.launch-btn {
  position: relative;
  width: 100%;
  max-width: 520px;
  padding: 20px 40px;
  background: linear-gradient(135deg, #0d2437, #0f3460);
  border: 1px solid #3b82f6;
  border-radius: 10px;
  color: #93c5fd;
  font-family: inherit;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 0.06em;
  cursor: pointer;
  transition: all 0.25s ease;
  box-shadow: 0 0 20px #3b82f620, inset 0 0 20px #3b82f610;
  overflow: hidden;
}

.launch-btn::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, transparent 40%, #3b82f615);
  pointer-events: none;
}

.launch-btn:hover:not(:disabled) {
  border-color: #60a5fa;
  box-shadow: 0 0 30px #3b82f640, inset 0 0 30px #3b82f618;
  color: #bfdbfe;
  transform: translateY(-1px);
}

.launch-btn:disabled {
  cursor: not-allowed;
  opacity: 0.85;
}

.launch-btn.firing {
  border-color: #f59e0b;
  box-shadow: 0 0 25px #f59e0b40;
  color: #fcd34d;
  animation: fire-pulse 1s ease-in-out infinite;
}

.launch-btn.done {
  border-color: #4af0c8;
  box-shadow: 0 0 25px #4af0c830;
  color: #4af0c8;
}

@keyframes fire-pulse {
  0%, 100% { box-shadow: 0 0 20px #f59e0b30; }
  50%       { box-shadow: 0 0 40px #f59e0b60; }
}

.btn-inner {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
}

.btn-icon {
  font-size: 18px;
}

.spinner {
  display: inline-block;
  width: 16px;
  height: 16px;
  border: 2px solid #f59e0b40;
  border-top-color: #f59e0b;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

/* ===================== 统计面板 ===================== */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}

@media (max-width: 700px) {
  .stats-grid { grid-template-columns: repeat(3, 1fr); }
}

.stat-item {
  text-align: center;
  padding: 12px 8px;
  background: #0b0f1a;
  border: 1px solid #1e2940;
  border-radius: 8px;
}

.stat-val {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.02em;
  line-height: 1;
  margin-bottom: 6px;
}

.stat-val.success  { color: #4af0c8; }
.stat-val.fail     { color: #f87171; }
.stat-val.total    { color: #93c5fd; }
.stat-val.duration { color: #fbbf24; }
.stat-val.qps      { color: #a78bfa; }
.stat-val.rate     { color: #4af0c8; }
.stat-val.rate.danger { color: #f87171; }

.stat-label {
  font-size: 11px;
  color: #4a5980;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

/* 进度条 */
.progress-wrap {
  width: 100%;
  height: 6px;
  background: #0b0f1a;
  border-radius: 3px;
  overflow: hidden;
  margin-bottom: 6px;
}

.progress-bar {
  height: 100%;
  background: linear-gradient(90deg, #3b82f6, #4af0c8);
  border-radius: 3px;
  transition: width 0.3s ease;
  box-shadow: 0 0 8px #4af0c860;
}

.progress-bar.complete {
  background: linear-gradient(90deg, #4af0c8, #22d3ee);
}

.progress-label {
  font-size: 12px;
  color: #4a5980;
  text-align: right;
}

/* ===================== 日志面板 ===================== */
.log-card {
  padding-bottom: 20px;
}

.log-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.log-header .card-title {
  margin-bottom: 0;
}

.clear-btn {
  font-family: inherit;
  font-size: 12px;
  color: #4a5980;
  background: transparent;
  border: 1px solid #1e2940;
  border-radius: 4px;
  padding: 3px 10px;
  cursor: pointer;
  transition: color 0.2s, border-color 0.2s;
}

.clear-btn:hover {
  color: #c9d1e0;
  border-color: #3a4a6a;
}

.log-scroll {
  max-height: 280px;
  overflow-y: auto;
  scrollbar-width: thin;
  scrollbar-color: #1e2940 transparent;
}

.log-scroll::-webkit-scrollbar {
  width: 4px;
}

.log-scroll::-webkit-scrollbar-thumb {
  background: #1e2940;
  border-radius: 2px;
}

.log-line {
  display: flex;
  gap: 12px;
  padding: 4px 0;
  font-size: 12px;
  line-height: 1.6;
  border-bottom: 1px solid #0f172a;
}

.log-time {
  color: #2e3f60;
  white-space: nowrap;
  flex-shrink: 0;
}

.log-line.info    .log-msg { color: #6b7fa3; }
.log-line.success .log-msg { color: #4af0c8; }
.log-line.warn    .log-msg { color: #fbbf24; }
.log-line.error   .log-msg { color: #f87171; }
</style>
