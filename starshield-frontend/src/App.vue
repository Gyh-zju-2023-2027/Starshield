<template>
  <div id="root">
    <TestMock />
  </div>
</template>

<script setup>
import TestMock from './views/TestMock.vue'
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
</style>
