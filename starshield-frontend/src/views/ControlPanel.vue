<template>
  <div class="min-h-full bg-[#020617] px-6 pb-14 pt-9 md:px-11">
    <header class="mb-10 max-w-5xl border-b border-white/10 pb-8">
      <p class="text-[11px] font-bold uppercase tracking-[0.22em] text-cyan-400/90">Aether Command · Rule Console</p>
      <h1 class="mt-2 font-display text-2xl font-semibold text-slate-100">规则控制台</h1>
      <p class="mt-2 max-w-2xl text-sm leading-relaxed text-slate-400">
        敏感词列表与 Prompt 热更新，对齐下游 LLM / 过滤引擎运行时配置。
      </p>
    </header>

    <div class="grid gap-6 xl:grid-cols-2">
      <!-- security / 词条 -->
      <section
        class="flex flex-col rounded-3xl border border-white/10 bg-gradient-to-br from-slate-900/90 to-[#070b18] p-7 shadow-xl ring-1 ring-cyan-500/10 backdrop-blur-[10px]"
      >
        <div class="mb-6 flex flex-wrap items-start gap-4">
          <div class="flex h-12 w-12 items-center justify-center rounded-2xl border border-violet-500/25 bg-violet-950/60 text-violet-300 ring-1 ring-violet-500/15">
            <span class="material-symbols-outlined text-[28px]">security</span>
          </div>
          <div>
            <h2 class="font-display text-lg font-semibold text-slate-100">敏感词管理</h2>
            <p class="mt-1 text-xs text-slate-400">{{ wordChipPreview.length }} 个词条（节选预览）</p>
          </div>
        </div>
        <div class="mb-4 flex flex-wrap gap-2">
          <span
            v-for="(w, i) in wordChipPreview.slice(0, 12)"
            :key="i"
            class="rounded-full border border-white/[0.08] bg-slate-950/60 px-3 py-1 text-xs font-medium text-slate-300 ring-1 ring-white/[0.04]"
          >
            {{ w }}
          </span>
        </div>
        <el-input v-model="wordsText" type="textarea" :rows="10" placeholder="每行一个词，支持批量粘贴" />
        <el-button type="primary" round class="mt-5 self-start px-8 !py-6 !font-semibold shadow-md" @click="saveWords">
          <span class="material-symbols-outlined mr-1 align-middle text-lg leading-none">cloud_upload</span>
          更新词库
        </el-button>
      </section>

      <!-- prompt / terminal -->
      <section
        class="flex flex-col rounded-3xl border border-slate-700/80 bg-gradient-to-br from-slate-950 via-slate-900 to-[#051018] p-7 text-slate-100 shadow-2xl ring-1 ring-cyan-500/10"
      >
        <div class="mb-5 flex flex-wrap items-center gap-3">
          <span class="material-symbols-outlined text-cyan-300">terminal</span>
          <h2 class="font-display text-lg font-semibold tracking-tight">系统 Prompt · 热替换</h2>
        </div>
        <p class="mb-4 font-mono text-[11px] uppercase tracking-[0.2em] text-cyan-200/70">prompt.config · live-edit</p>
        <el-input v-model="promptText" type="textarea" :rows="16" placeholder="写入审核 Prompt / JSON 皆可" class="prompt-dark" />
        <el-button
          type="primary"
          plain
          class="mt-6 self-start rounded-full !border-emerald-500/70 !bg-emerald-950/90 !font-semibold !text-emerald-100"
          @click="savePrompt"
        >
          <span class="material-symbols-outlined mr-2 align-middle text-lg">rocket_launch</span>
          部署变更
        </el-button>
      </section>
    </div>

    <aside class="mt-8 rounded-3xl border border-white/10 bg-slate-900/70 px-6 py-5 ring-1 ring-white/[0.06] backdrop-blur-[8px]">
      <div class="flex flex-wrap items-center justify-between gap-4 text-sm">
        <div class="flex items-center gap-2 text-slate-400">
          <span class="material-symbols-outlined text-cyan-500/90">insights</span>
          <span>配置将同步至网关；生产环境请以版本发布为准。</span>
        </div>
        <span
          class="rounded-full border border-emerald-500/30 bg-emerald-950/50 px-4 py-1.5 text-xs font-bold uppercase tracking-wide text-emerald-300 ring-1 ring-emerald-500/20"
        >
          Architectural Integrity OK
        </span>
      </div>
    </aside>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getPrompt, getSensitiveWords, updatePrompt, updateSensitiveWords } from '../api/control'

const wordsText = ref('')
const promptText = ref('')

const wordChipPreview = computed(() =>
  wordsText.value
    .split('\n')
    .map((x) => x.trim())
    .filter(Boolean)
    .slice(0, 32)
)

async function load() {
  const [w, p] = await Promise.all([getSensitiveWords(), getPrompt()])
  if (w.code === 200) {
    wordsText.value = (w.data || []).join('\n')
  }
  if (p.code === 200) {
    promptText.value = p.data?.prompt || ''
  }
}

async function saveWords() {
  const words = wordsText.value
    .split('\n')
    .map((x) => x.trim())
    .filter(Boolean)
  const res = await updateSensitiveWords(words)
  if (res.code === 200) {
    ElMessage.success('敏感词已更新')
  }
}

async function savePrompt() {
  const res = await updatePrompt(promptText.value)
  if (res.code === 200) {
    ElMessage.success('Prompt 已更新')
  }
}

onMounted(load)
</script>

<style scoped>
:deep(.prompt-dark .el-textarea__inner) {
  background: #0b1324 !important;
  color: #e2e8f0 !important;
  border: 1px solid #1e293b !important;
  border-radius: 12px !important;
  font-family: ui-monospace, JetBrains Mono, monospace;
  font-size: 12px !important;
  line-height: 1.55 !important;
}
</style>
