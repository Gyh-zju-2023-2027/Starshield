<template>
  <div id="root" class="ss-root flex min-h-screen font-sans text-slate-200">
    <aside
      class="ss-sidebar hidden shrink-0 flex-col border-r border-white/6 px-4 py-6 shadow-[4px_0_48px_rgba(0,0,0,0.45)] lg:flex"
    >
      <div class="mb-8 rounded-[28px] border border-white/10 bg-white/[0.03] px-4 py-4 ring-1 ring-white/[0.04]">
        <div class="flex items-center gap-2">
          <span class="h-2 w-2 rounded-full bg-sky-300 shadow-[0_0_14px_rgba(94,230,255,0.5)]" />
          <p class="font-display text-[10px] font-bold uppercase tracking-[0.32em] text-sky-100">StarShield</p>
        </div>
        <p class="mt-2 font-display text-xl font-semibold tracking-tight text-white">星盾 · 中台</p>
        <p class="mt-2 text-xs leading-6 text-slate-400">舆情接入、审核决策、封禁分析和日报输出集中在一个工作面板里。</p>
      </div>

      <div class="mb-4 px-2">
        <p class="text-[11px] font-semibold uppercase tracking-[0.18em] text-slate-500">Workbench</p>
      </div>

      <nav class="flex flex-col gap-2">
        <button
          v-for="item in nav"
          :key="item.id"
          type="button"
          class="ss-nav-button flex items-center gap-3 rounded-2xl px-3 py-3 text-left text-[13px] font-medium transition-all duration-200"
          :class="
            tab === item.id
              ? 'ss-nav-button--active text-sky-50 shadow-[inset_0_1px_0_rgba(103,232,249,0.12)] ring-1 ring-sky-400/20'
              : 'text-slate-400 hover:bg-white/5 hover:text-slate-100'
          "
          @click="tab = item.id"
        >
          <span class="material-symbols-outlined shrink-0 text-[22px]" :class="tab === item.id ? 'text-sky-200' : 'text-slate-500'">{{
            item.icon
          }}</span>
          <span class="flex min-w-0 flex-1 flex-col">
            <span>{{ item.label }}</span>
            <span class="mt-0.5 text-[11px] font-normal leading-5 text-slate-500">{{ item.desc }}</span>
          </span>
        </button>
      </nav>

      <div class="mt-auto space-y-3 px-2 pt-6">
        <div class="flex items-center justify-between rounded-2xl border border-sky-400/14 bg-sky-400/[0.08] px-3 py-2 text-xs text-sky-100">
          <span class="inline-flex items-center gap-2">
            <span class="h-2 w-2 rounded-full bg-sky-300 shadow-[0_0_12px_rgba(94,230,255,0.7)]" />
            Workspace Ready
          </span>
          <span class="text-sky-100/65">Vue + ECharts</span>
        </div>
      </div>
    </aside>

    <main class="relative min-h-screen min-w-0 flex-1 overflow-x-hidden">
      <div class="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_80%_50%_at_50%_-20%,rgba(34,211,238,0.08),transparent)]" />
      <div class="relative z-0 min-h-full">
        <header class="sticky top-0 z-20 border-b border-white/6 bg-slate-950/55 px-5 py-4 backdrop-blur-xl lg:hidden">
          <div class="flex items-center justify-between gap-4">
            <div>
              <p class="text-[10px] font-bold uppercase tracking-[0.28em] text-sky-200">StarShield</p>
              <p class="mt-1 font-display text-lg text-slate-50">{{ activeItem.label }}</p>
            </div>
            <span class="ss-chip ss-chip--accent">{{ nav.length }} 个视图</span>
          </div>
          <div class="ss-scrollbar mt-4 flex gap-2 overflow-x-auto pb-1">
            <button
              v-for="item in nav"
              :key="item.id"
              type="button"
              class="whitespace-nowrap rounded-full border px-3 py-1.5 text-xs transition"
              :class="tab === item.id ? 'border-sky-400/20 bg-sky-400/[0.10] text-sky-50' : 'border-white/10 bg-white/[0.03] text-slate-400'"
              @click="tab = item.id"
            >
              {{ item.label }}
            </button>
          </div>
        </header>
        <TestMock v-if="tab === 'test'" />
        <AdminReview v-else-if="tab === 'admin'" />
        <DashboardBoard v-else-if="tab === 'dashboard'" />
        <BanAnalytics v-else-if="tab === 'ban'" />
        <DailyReport v-else-if="tab === 'report'" />
        <ControlPanel v-else-if="tab === 'control'" />
        <CrawlConsole v-else-if="tab === 'crawl'" @navigate="tab = $event" />
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import TestMock from './views/TestMock.vue'
import AdminReview from './views/AdminReview.vue'
import DashboardBoard from './views/DashboardBoard.vue'
import BanAnalytics from './views/BanAnalytics.vue'
import ControlPanel from './views/ControlPanel.vue'
import DailyReport from './views/DailyReport.vue'
import CrawlConsole from './views/CrawlConsole.vue'

const tab = ref('test')

const nav = [
  { id: 'test', label: '压测入口', icon: 'rocket_launch', desc: '模拟入口流量与日志' },
  { id: 'crawl', label: '爬取控制台', icon: 'download', desc: 'B站数据采集与任务管理' },
  { id: 'admin', label: '审核后台', icon: 'fact_check', desc: '处理待复核记录' },
  { id: 'dashboard', label: '实时大屏', icon: 'monitoring', desc: '监控实时风险态势' },
  { id: 'ban', label: '封禁分析', icon: 'block', desc: '分析封禁样本结构' },
  { id: 'control', label: '规则控制台', icon: 'tune', desc: '更新词库与 Prompt' },
  { id: 'report', label: '每日战报', icon: 'summarize', desc: '查看日报与导出' }
]

const activeItem = computed(() => nav.find((item) => item.id === tab.value) || nav[0])
</script>

<style>
:root {
  --bg-main: #0a1020;
  --bg-main-deep: #070b16;
  --bg-panel: #111827;
  --bg-card: #151e2f;
  --primary: #3b82f6;
  --primary-strong: #2f6bff;
  --accent-cyan: #5ee6ff;
  --accent-violet: #6c63ff;
  --text-main: #eaf2ff;
  --text-secondary: #8fa3bf;
  --text-muted: #5f718a;
  --border-soft: rgba(120, 160, 220, 0.18);
  --danger: #ff6b57;
  --warning: #f7b955;
  --success: #42d392;
  --font-display: 'Manrope', 'Inter', system-ui, sans-serif;
  --font-body: 'Inter', system-ui, sans-serif;
}

html.dark {
  --el-color-primary: var(--primary);
  --el-color-primary-light-3: #5b97f8;
  --el-color-primary-light-5: #78a9fa;
  --el-color-primary-light-7: #9cc1fb;
  --el-color-primary-light-8: #bed7fd;
  --el-color-primary-light-9: #deecfe;
  --el-color-primary-dark-2: var(--primary-strong);
  --el-color-success: var(--success);
  --el-color-warning: var(--warning);
  --el-color-danger: var(--danger);
  --el-bg-color-page: var(--bg-main);
  --el-bg-color: var(--bg-panel);
  --el-bg-color-overlay: var(--bg-card);
  --el-fill-color-blank: var(--bg-panel);
}

html.dark .el-drawer {
  --el-drawer-bg-color: var(--bg-panel);
}
html.dark .el-drawer__body {
  background-color: var(--el-drawer-bg-color);
}

* {
  box-sizing: border-box;
}

body {
  margin: 0;
  font-family: var(--font-body);
  color: var(--text-main);
  background:
    radial-gradient(circle at 20% 10%, rgba(47, 107, 255, 0.18), transparent 28%),
    radial-gradient(circle at 80% 0%, rgba(94, 230, 255, 0.1), transparent 24%),
    linear-gradient(180deg, var(--bg-main) 0%, var(--bg-main-deep) 100%);
}

#app {
  min-height: 100vh;
}

.ss-root {
  background:
    linear-gradient(180deg, rgba(17, 24, 39, 0.14), rgba(10, 16, 32, 0.22)),
    transparent;
}

.ss-sidebar {
  width: 300px;
  background:
    radial-gradient(circle at top left, rgba(47, 107, 255, 0.1), transparent 26%),
    linear-gradient(180deg, rgba(10, 16, 32, 0.96), rgba(7, 11, 22, 0.9));
}

.ss-nav-button {
  width: 100%;
  appearance: none;
  border: 1px solid var(--border-soft);
  background: rgba(255, 255, 255, 0.02);
}

.ss-nav-button:hover {
  border-color: rgba(120, 160, 220, 0.28);
}

.ss-nav-button--active {
  border-color: rgba(59, 130, 246, 0.34);
  background:
    linear-gradient(90deg, rgba(47, 107, 255, 0.24), rgba(94, 230, 255, 0.08)),
    rgba(255, 255, 255, 0.04);
}
</style>
