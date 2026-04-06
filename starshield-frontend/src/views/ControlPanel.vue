<template>
  <div class="control-wrap">
    <div class="col">
      <h3>敏感词热管理</h3>
      <el-input v-model="wordsText" type="textarea" :rows="8" placeholder="每行一个词" />
      <el-button type="primary" @click="saveWords" style="margin-top: 10px">保存敏感词</el-button>
    </div>

    <div class="col">
      <h3>Prompt 热管理</h3>
      <el-input v-model="promptText" type="textarea" :rows="8" placeholder="输入审核 Prompt" />
      <el-button type="primary" @click="savePrompt" style="margin-top: 10px">保存 Prompt</el-button>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getPrompt, getSensitiveWords, updatePrompt, updateSensitiveWords } from '../api/control'

const wordsText = ref('')
const promptText = ref('')

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
.control-wrap {
  min-height: 100vh;
  background: #080c16;
  color: #dbe7ff;
  padding: 16px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.col {
  border: 1px solid #2b395c;
  border-radius: 10px;
  padding: 12px;
  background: #0d1424;
}
</style>
