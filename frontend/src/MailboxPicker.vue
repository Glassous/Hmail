<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import Icon from './Icon.vue'
import { gsap, Flip, motionDuration } from './motion'

const props = defineProps<{ accounts: { id: string; email: string }[]; modelValue: string; hidden?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const root = ref<HTMLElement>(), card = ref<HTMLElement>(), trigger = ref<HTMLButtonElement>()
const opened = ref(false), highlighted = ref('')
let animation: gsap.core.Timeline | undefined, revision = 0, context: gsap.Context | undefined

async function change(open: boolean, selected?: string, restoreFocus = false) {
  if (!card.value || (!props.accounts.length && open)) return
  const current = ++revision
  animation?.progress(1).kill()
  const state = Flip.getState([card.value, ...card.value.querySelectorAll('[data-flip-id]')])
  if (selected) emit('update:modelValue', selected)
  opened.value = open
  if (open) highlighted.value = props.modelValue || props.accounts[0]?.id || ''
  await nextTick()
  if (current !== revision || !card.value) return
  context?.add(() => {
    animation = Flip.from(state, {
      targets: [card.value!, ...card.value!.querySelectorAll('[data-flip-id]')],
      duration: motionDuration(), ease: 'power2.out', absoluteOnLeave: true,
      onComplete: () => { if (opened.value && current === revision) focusOption() },
      onEnter: elements => gsap.fromTo(elements, { autoAlpha: 0, y: -4 }, { autoAlpha: 1, y: 0, duration: motionDuration() }),
      onLeave: elements => gsap.to(elements, { autoAlpha: 0, duration: motionDuration() * 0.6 }),
    })
  })
  if (!open && restoreFocus) trigger.value?.focus()
}
function focusOption() {
  const element = Array.from(card.value?.querySelectorAll<HTMLElement>('[role="option"]') || []).find(el => el.dataset.account === highlighted.value)
  element?.focus({ preventScroll: true })
  element?.scrollIntoView({ block: 'nearest' })
}
function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && opened.value) {
    event.preventDefault(); event.stopPropagation(); void change(false, undefined, true); return
  }
  if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  if (!opened.value) { void change(true); return }
  const index = props.accounts.findIndex(a => a.id === highlighted.value)
  const next = event.key === 'Home' ? 0 : event.key === 'End' ? props.accounts.length - 1 : (index + (event.key === 'ArrowDown' ? 1 : -1) + props.accounts.length) % props.accounts.length
  highlighted.value = props.accounts[next]?.id || ''
  focusOption()
}
function outside(event: PointerEvent) {
  if (opened.value && !root.value?.contains(event.target as Node)) void change(false)
}
function focusout(event: FocusEvent) {
  if (opened.value && event.relatedTarget && !root.value?.contains(event.relatedTarget as Node)) void change(false)
}
watch(() => props.hidden, value => { if (value && opened.value) void change(false) })
watch(() => props.accounts, () => { if (opened.value) void change(false) })
onMounted(() => { context = gsap.context(() => {}, root.value); document.addEventListener('pointerdown', outside) })
onUnmounted(() => { revision++; animation?.kill(); context?.revert(); document.removeEventListener('pointerdown', outside) })
</script>

<template>
  <div ref="root" class="mailbox-picker" @keydown="keydown" @focusout="focusout">
    <div ref="card" class="mailbox-card surface" :class="{ 'is-open': opened }" data-flip-id="mailbox-card">
      <button ref="trigger" type="button" class="mailbox-trigger" :disabled="!accounts.length" :aria-expanded="opened" aria-haspopup="listbox" aria-controls="mailbox-options" @click="change(!opened)">
        <span>当前邮箱</span><Icon name="chevron" :size="14" :class="opened ? '-rotate-90' : 'rotate-90'"/>
      </button>
      <div id="mailbox-options" class="mailbox-options" :role="opened ? 'listbox' : undefined" aria-label="选择邮箱">
        <button v-for="account in accounts" v-show="opened || account.id === modelValue" :key="account.id" type="button" :data-flip-id="'mailbox-' + account.id" :data-account="account.id" class="mailbox-option" :class="{ 'is-selected': opened && account.id === modelValue }" :role="opened ? 'option' : undefined" :aria-selected="opened ? account.id === modelValue : undefined" :tabindex="opened ? (highlighted === account.id ? 0 : -1) : 0" v-tip="account.email" :aria-label="opened ? account.email : '切换邮箱：' + account.email" @click="opened ? change(false, account.id, true) : change(true)">
          <span class="mailbox-avatar">{{ account.email[0]?.toUpperCase() }}</span><span class="min-w-0 flex-1 truncate">{{ account.email }}</span><Icon v-if="opened && account.id === modelValue" name="check" :size="14" class="shrink-0"/>
        </button>
        <p v-if="!accounts.length" class="px-3 pb-3 text-xs text-slate-400">尚未连接邮箱</p>
      </div>
    </div>
  </div>
</template>
