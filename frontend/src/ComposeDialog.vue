<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { gsap, motionDuration } from './motion'

defineProps<{ open: boolean }>()
const emit = defineEmits<{ stash: [] }>()
const overlay = ref<HTMLElement>()
let animation: gsap.core.Timeline | undefined
let returnFocus: HTMLElement | null = null
let background: HTMLElement | null = null
let oldInert = false, oldOverflow = ''
let locked = false
function lock() {
  if (locked) return
  locked = true
  returnFocus = document.activeElement as HTMLElement
  background = document.getElementById('mail-workspace')
  oldInert = background?.inert || false
  if (background) background.inert = true
  oldOverflow = document.body.style.overflow
  document.body.style.overflow = 'hidden'
}
function release() {
  if (!locked) return
  locked = false
  if (background) background.inert = oldInert
  document.body.style.overflow = oldOverflow
  const fallback = document.querySelector<HTMLElement>('.compose-stash') || background?.querySelector<HTMLElement>('header button, header input')
  const target = returnFocus?.isConnected && returnFocus.getClientRects().length && !returnFocus.closest('aside.-translate-x-full') ? returnFocus : fallback
  target?.focus({ preventScroll: true })
}
function enter(element: Element, done: () => void) {
  animation?.kill(); lock()
  const panel = element.querySelector<HTMLElement>('[role="dialog"]')!
  animation = gsap.timeline({ onComplete: done })
    .fromTo(element, { autoAlpha: 0 }, { autoAlpha: 1, duration: motionDuration() }, 0)
    .fromTo(panel, { y: 12, scale: 0.97 }, { y: 0, scale: 1, duration: motionDuration(), ease: 'power2.out' }, 0)
  ;(panel.querySelector<HTMLElement>('input:not(:disabled)') || panel).focus({ preventScroll: true })
}
function leave(element: Element, done: () => void) {
  animation?.kill()
  animation = gsap.timeline({ onComplete: () => { release(); done() } })
    .to(element, { autoAlpha: 0, duration: motionDuration() }, 0)
    .to(element.querySelector('[role="dialog"]'), { y: 8, scale: 0.98, duration: motionDuration(), ease: 'power2.in' }, 0)
}
function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { event.preventDefault(); event.stopPropagation(); emit('stash'); return }
  if (event.key !== 'Tab') return
  const nodes = Array.from(overlay.value?.querySelectorAll<HTMLElement>('button:not(:disabled), input:not(:disabled), textarea:not(:disabled), a[href], [tabindex="0"]') || []).filter(el => el.getClientRects().length && !el.classList.contains('sr-only'))
  const first = nodes[0], last = nodes[nodes.length - 1]
  if (!first) { event.preventDefault(); return }
  if (!nodes.includes(document.activeElement as HTMLElement)) { event.preventDefault(); (event.shiftKey ? last : first)?.focus() }
  else if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
}
onUnmounted(() => { animation?.kill(); release() })
</script>

<template>
  <Teleport to="body">
    <Transition :css="false" @enter="enter" @leave="leave" @enter-cancelled="animation?.kill()" @leave-cancelled="animation?.kill()">
      <div v-if="open" ref="overlay" class="compose-overlay" @keydown="keydown">
        <section class="compose-dialog surface" role="dialog" aria-modal="true" aria-label="写邮件" tabindex="-1"><slot/></section>
      </div>
    </Transition>
  </Teleport>
</template>
