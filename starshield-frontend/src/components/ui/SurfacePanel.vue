<template>
  <section
    class="ss-surface"
    :class="[
      tone === 'muted' ? 'ss-surface--muted' : '',
      dense ? 'ss-surface--dense' : ''
    ]"
  >
    <slot />
  </section>
</template>

<script setup>
defineProps({
  tone: {
    type: String,
    default: 'default'
  },
  dense: {
    type: Boolean,
    default: false
  }
})
</script>
