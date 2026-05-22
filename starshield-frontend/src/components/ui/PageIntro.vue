<template>
  <header class="ss-page-intro">
    <div class="min-w-0 flex-1">
      <p v-if="eyebrow" class="ss-page-intro__eyebrow">{{ eyebrow }}</p>
      <div class="mt-3 flex flex-wrap items-start gap-4">
        <div class="min-w-0 flex-1">
          <h1 class="ss-page-intro__title">{{ title }}</h1>
          <p v-if="description" class="ss-page-intro__desc">
            {{ description }}
          </p>
        </div>
        <div v-if="$slots.meta" class="shrink-0 self-start lg:-mt-8">
          <slot name="meta" />
        </div>
      </div>
    </div>
  </header>
</template>

<script setup>
defineProps({
  eyebrow: {
    type: String,
    default: ''
  },
  title: {
    type: String,
    required: true
  },
  description: {
    type: String,
    default: ''
  }
})
</script>
