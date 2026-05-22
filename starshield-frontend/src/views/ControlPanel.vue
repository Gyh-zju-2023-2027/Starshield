<template>
  <div class="ss-page">
    <PageIntro
      eyebrow="Aether Command | Rule Console"
      title="规则控制台"
      description="在这里维护敏感词规则和审核 Prompt。两个模块保持相同的信息层级，方便并排编辑和快速发布。"
    >
      <template #meta>
        <div class="grid min-w-[260px] grid-cols-2 gap-3">
          <MetricCard label="词库词条" :value="wordChipPreview.length" helper="当前预览数量" icon="policy" />
          <MetricCard label="Prompt" :value="promptText ? '已加载' : '未加载'" helper="运行时版本" icon="terminal" accent="amber" />
        </div>
      </template>
    </PageIntro>

    <div class="grid gap-6 xl:grid-cols-2">
      <SurfacePanel class="flex h-full flex-col">
        <div class="mb-4 grid grid-cols-[48px_minmax(0,1fr)] items-center gap-4">
          <div class="flex h-12 w-12 items-center justify-center rounded-2xl border border-sky-400/18 bg-sky-400/[0.08] text-sky-200 ring-1 ring-sky-400/10">
            <span class="material-symbols-outlined text-[28px]">security</span>
          </div>
          <div class="min-w-0 self-center">
            <h2 class="font-display text-lg font-semibold leading-5 text-slate-100">敏感词词库</h2>
            <p class="mt-1 text-xs leading-4 text-slate-400">每行一条，保存后会覆盖当前规则集。</p>
          </div>
        </div>

        <div class="mb-4 flex min-h-[56px] flex-wrap content-start gap-2">
          <span
            v-for="(word, index) in wordChipPreview"
            :key="`${word}-${index}`"
            class="rounded-2xl border border-white/[0.08] bg-slate-950/60 px-3 py-1.5 text-xs font-medium leading-none text-slate-300 ring-1 ring-white/[0.04]"
          >
            {{ word }}
          </span>
        </div>

        <el-input
          v-model="wordsText"
          type="textarea"
          :rows="14"
          placeholder="每行一个词，支持批量粘贴"
          class="console-textarea"
        />

        <el-button
          type="primary"
          class="ss-button-primary mt-5 self-start !rounded-2xl !border-none !px-6 !py-6 !font-semibold"
          @click="saveWords"
        >
          <span class="material-symbols-outlined text-lg leading-none">cloud_upload</span>
          更新词库
        </el-button>
      </SurfacePanel>

      <SurfacePanel class="flex h-full flex-col">
        <div class="mb-4 grid grid-cols-[48px_minmax(0,1fr)] items-center gap-4">
          <div class="flex h-12 w-12 items-center justify-center rounded-2xl border border-sky-400/18 bg-sky-400/[0.08] text-sky-200 ring-1 ring-sky-400/10">
            <span class="material-symbols-outlined text-[28px]">terminal</span>
          </div>
          <div class="min-w-0 self-center">
            <h2 class="font-display text-lg font-semibold leading-5 text-slate-100">系统 Prompt 热替换</h2>
            <p class="mt-1 text-xs leading-4 text-slate-400">支持直接编辑运行中的审核 Prompt 或 JSON 配置。</p>
          </div>
        </div>

        <div class="mb-4 flex min-h-[56px] items-center text-[11px] uppercase tracking-[0.2em] text-slate-500">
          prompt.config | live-edit
        </div>

        <el-input
          v-model="promptText"
          type="textarea"
          :rows="14"
          placeholder="写入审核 Prompt / JSON 均可"
          class="console-textarea"
        />

        <el-button
          type="primary"
          class="ss-button-primary mt-5 self-start !rounded-2xl !border-none !px-6 !py-6 !font-semibold"
          @click="savePrompt"
        >
          <span class="material-symbols-outlined text-lg leading-none">rocket_launch</span>
          发布变更
        </el-button>
      </SurfacePanel>
    </div>

    <SurfacePanel dense class="mt-8">
      <div class="flex flex-wrap items-center justify-between gap-4 text-sm">
        <div class="flex items-center gap-2 text-slate-400">
          <span class="material-symbols-outlined text-sky-300">insights</span>
          <span>配置会同步到网关。生产环境建议按版本发布，避免多人同时覆盖。</span>
        </div>
        <span class="rounded-full border border-sky-400/18 bg-sky-400/[0.08] px-4 py-1.5 text-xs font-bold uppercase tracking-wide text-sky-100 ring-1 ring-sky-400/10">
          Rules Synced View
        </span>
      </div>
    </SurfacePanel>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getPrompt, getSensitiveWords, updatePrompt, updateSensitiveWords } from '../api/control'
import MetricCard from '../components/ui/MetricCard.vue'
import PageIntro from '../components/ui/PageIntro.vue'
import SurfacePanel from '../components/ui/SurfacePanel.vue'

const wordsText = ref('')
const promptText = ref('')

const wordChipPreview = computed(() =>
  wordsText.value
    .split('\n')
    .map((item) => item.trim())
    .filter(Boolean)
)

async function load() {
  const [wordsRes, promptRes] = await Promise.all([getSensitiveWords(), getPrompt()])

  if (wordsRes.code === 200) {
    wordsText.value = (wordsRes.data || []).join('\n')
  }

  if (promptRes.code === 200) {
    promptText.value = promptRes.data?.prompt || ''
  }
}

async function saveWords() {
  const words = wordsText.value
    .split('\n')
    .map((item) => item.trim())
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
:deep(.console-textarea .el-textarea__inner) {
  min-height: 340px !important;
  background: rgba(7, 11, 22, 0.92) !important;
  color: #eaf2ff !important;
  border: 1px solid rgba(120, 160, 220, 0.18) !important;
  border-radius: 20px !important;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.03);
  font-family: ui-monospace, SFMono-Regular, 'JetBrains Mono', monospace;
  font-size: 12px !important;
  line-height: 1.65 !important;
}

:deep(.console-textarea .el-textarea__inner:focus) {
  border-color: rgba(94, 230, 255, 0.3) !important;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.03),
    0 0 0 1px rgba(94, 230, 255, 0.14) !important;
}
</style>
