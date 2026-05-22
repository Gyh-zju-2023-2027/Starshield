<template>
  <div class="ss-metric-card" :class="accentClass">
    <div class="flex items-start justify-between gap-4">
      <div class="min-w-0">
        <p class="ss-metric-card__label">{{ label }}</p>
        <p class="ss-metric-card__value">{{ value }}</p>
        <p v-if="helper" class="ss-metric-card__helper">{{ helper }}</p>
      </div>
      <span v-if="icon" class="material-symbols-outlined ss-metric-card__icon">
        {{ icon }}
      </span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  label: {
    type: String,
    required: true
  },
  value: {
    type: [String, Number],
    required: true
  },
  helper: {
    type: String,
    default: ''
  },
  icon: {
    type: String,
    default: ''
  },
  accent: {
    type: String,
    default: 'cyan'
  }
})

const accentClass = computed(() => `ss-metric-card--${props.accent}`)
</script>
