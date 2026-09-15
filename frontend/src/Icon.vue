<script setup lang="ts">
defineProps<{ name: string; size?: number }>()
const paths: Record<string, string> = {
  menu: 'M4 6h16M4 12h16M4 18h16', search: 'm21 21-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  mail: 'M3 5h18v14H3zM3 5l9 8 9-8', inbox: 'M4 3h16l2 12v6H2v-6L4 3ZM2 15h6l2 3h4l2-3h6',
  send: 'm22 2-7 20-4-9L2 9 22 2ZM22 2 11 13', edit: 'm16 3 5 5-12 12-6 1 1-6L16 3ZM13 6l5 5',
  star: 'm12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-3-5.6 3 1.1-6.2L2 9.6l6.2-.9L12 3Z',
  trash: 'M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7M14 10v7',
  archive: 'M3 3h18v4H3zM5 7v14h14V7M9 11h6', tag: 'M3 3h8l10 10-8 8L3 11V3ZM7 7h.01',
  refresh: 'M20 7V3l-3 3a8 8 0 1 0 3 11M20 7h-5', chevron: 'm9 5 7 7-7 7', back: 'm14 6-6 6 6 6M8 12h13',
  close: 'm6 6 12 12M6 18 18 6', plus: 'M12 5v14M5 12h14', settings: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8M4 4l16 16M4 20 20 4M12 2v4M12 18v4M2 12h4M18 12h4',
  sun: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8M12 2v2M12 20v2M2 12h2M20 12h2M5 5l1 1M18 18l1 1M5 19l1-1M18 6l1-1',
  moon: 'M21 13a9 9 0 0 1-10-10A9 9 0 1 0 21 13Z', system: 'M20 3H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2ZM8 21h8M12 17v4', attachment: 'm8 12 6-6a3 3 0 0 1 4 4l-8 8a5 5 0 0 1-7-7l8-8',
  logout: 'M9 4H3v16h6M14 7l5 5-5 5M8 12h13', shield: 'm12 2 9 4v6c0 5-9 10-9 10S3 17 3 12V6l9-4ZM8 12l3 3 5-6',
  alert: 'm12 3 10 18H2L12 3ZM12 9v5M12 17h.01', check: 'm5 12 4 4L19 6', reply: 'm9 5-7 7 7 7M2 12h12a7 7 0 0 1 7 7',
  replyAll: 'm8 5-6 7 6 7M13 5l-6 7 6 7M7 12h9a5 5 0 0 1 5 5', forward: 'm15 5 7 7-7 7M22 12H8a5 5 0 0 0-5 5',
  download: 'M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5', lock: 'M5 10h14v11H5zM8 10V6a4 4 0 0 1 8 0v4',
  grid: 'M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z',
}
</script>
<template><svg :width="size || 20" :height="size || 20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.mail" /></svg></template>
