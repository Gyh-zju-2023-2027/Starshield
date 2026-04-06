<template>
  <div id="root">
    <header class="top-nav">
      <button :class="{ active: tab === 'test' }" @click="tab = 'test'">压测入口</button>
      <button :class="{ active: tab === 'admin' }" @click="tab = 'admin'">审核后台</button>
      <button :class="{ active: tab === 'dashboard' }" @click="tab = 'dashboard'">实时大屏</button>
      <button :class="{ active: tab === 'control' }" @click="tab = 'control'">规则控制台</button>
    </header>

    <TestMock v-if="tab === 'test'" />
    <AdminReview v-else-if="tab === 'admin'" />
    <DashboardBoard v-else-if="tab === 'dashboard'" />
    <ControlPanel v-else />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import TestMock from './views/TestMock.vue'
import AdminReview from './views/AdminReview.vue'
import DashboardBoard from './views/DashboardBoard.vue'
import ControlPanel from './views/ControlPanel.vue'

const tab = ref('test')
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

#root {
  min-height: 100vh;
  background: #0b0f1a;
}

.top-nav {
  position: sticky;
  top: 0;
  z-index: 30;
  display: flex;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid #1e2b45;
  background: rgba(8, 12, 22, 0.92);
  backdrop-filter: blur(4px);
}

.top-nav button {
  border: 1px solid #2b395c;
  background: #10182a;
  color: #d8e4ff;
  padding: 8px 12px;
  border-radius: 8px;
  cursor: pointer;
}

.top-nav button.active {
  background: #1c2f56;
  border-color: #4e71c3;
}
</style>
