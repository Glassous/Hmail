<script setup lang="ts">
withDefaults(defineProps<{ size?: number }>(), { size: 36 })
const iconUrl = new URL('./assets/fiagmail.svg', import.meta.url).href
</script>

<template>
  <img :src="iconUrl" alt="" aria-hidden="true" :width="size" :height="size" class="shrink-0" />
</template>
