<template>
  <div id="root" class="flex min-h-screen bg-[#020617] font-sans text-slate-200">
    <aside
      class="flex w-[252px] shrink-0 flex-col border-r border-white/6 bg-gradient-to-b from-slate-950 via-[#070b18] to-[#020617] px-4 py-8 shadow-[4px_0_48px_rgba(0,0,0,0.45)]"
    >
      <div class="mb-10 px-2">
        <p class="bg-gradient-to-r from-cyan-300 to-sky-400 bg-clip-text font-display text-[10px] font-bold uppercase tracking-[0.32em] text-transparent">
          StarShield
        </p>
        <p class="mt-2 font-display text-lg font-semibold tracking-tight text-white">星盾 · 中台</p>
        <p class="mt-1.5 text-[11px] leading-relaxed text-slate-500">舆情接入 / 审核 / 可视化</p>
      </div>

      <nav class="flex flex-col gap-1">
        <button
          v-for="item in nav"
          :key="item.id"
          type="button"
          class="flex items-center gap-3 rounded-xl px-3 py-2.5 text-left text-[13px] font-medium transition-all duration-200"
          :class="
            tab === item.id
              ? 'bg-cyan-950/60 text-cyan-100 shadow-[inset_0_1px_0_rgba(103,232,249,0.12)] ring-1 ring-cyan-500/35'
              : 'text-slate-400 hover:bg-white/5 hover:text-slate-100'
          "
          @click="tab = item.id"
        >
          <span class="material-symbols-outlined shrink-0 text-[22px]" :class="tab === item.id ? 'text-cyan-300' : 'text-slate-500'">{{
            item.icon
          }}</span>
          {{ item.label }}
        </button>
      </nav>
    </aside>

    <main class="relative min-h-screen min-w-0 flex-1 overflow-x-hidden bg-[#020617]">
      <div class="pointer-events-none absolute inset-0 bg-[radial-gradient(ellipse_80%_50%_at_50%_-20%,rgba(34,211,238,0.08),transparent)]" />
      <div class="relative z-0 min-h-full">
        <TestMock v-if="tab === 'test'" />
        <AdminReview v-else-if="tab === 'admin'" />
        <DashboardBoard v-else-if="tab === 'dashboard'" />
        <ControlPanel v-else />
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import TestMock from './views/TestMock.vue'
import AdminReview from './views/AdminReview.vue'
import DashboardBoard from './views/DashboardBoard.vue'
import ControlPanel from './views/ControlPanel.vue'

const tab = ref('test')

const nav = [
  { id: 'test', label: '压测入口', icon: 'rocket_launch' },
  { id: 'admin', label: '审核后台', icon: 'fact_check' },
  { id: 'dashboard', label: '实时大屏', icon: 'monitoring' },
  { id: 'control', label: '规则控制台', icon: 'tune' }
]
</script>

<style>
:root {
  --dash-bg0: #030712;
  --dash-bg1: #0a101f;
  --dash-border: rgba(56, 189, 248, 0.14);
  --dash-glow: rgba(34, 211, 238, 0.12);
  --font-display: 'Manrope', 'Inter', system-ui, sans-serif;
  --font-body: 'Inter', system-ui, sans-serif;
}

/* 与侧栏/大屏一致：覆盖 Element Plus dark 默认主色与页面底 */
html.dark {
  --el-color-primary: #22d3ee;
  --el-color-primary-light-3: #06b6d4;
  --el-color-primary-light-5: #0891b2;
  --el-color-primary-light-7: #0e7490;
  --el-color-primary-light-8: #155e75;
  --el-color-primary-light-9: #164e63;
  --el-color-primary-dark-2: #67e8f9;
  --el-bg-color-page: #020617;
  --el-bg-color: #0f172a;
  --el-bg-color-overlay: #1e293b;
  --el-fill-color-blank: #0f172a;
}

/* 遮罩内弹层与侧栏同色底（teleport 到 body，不写各页 scoped） */
html.dark .el-drawer {
  --el-drawer-bg-color: #0f172a;
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
  background: #020617;
}

#app {
  min-height: 100vh;
}
</style>
