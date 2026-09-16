import { createApp, type Directive } from 'vue'
import App from './App.vue'
import './style.css'

const TIP_DELAY = 340
let tipNode: HTMLDivElement | null = null
let tipTarget: HTMLElement | null = null
let timerOwner: HTMLElement | null = null
let timer: ReturnType<typeof setTimeout> | undefined

function tipCard() {
  if (!tipNode) {
    tipNode = document.createElement('div')
    tipNode.className = 'tooltip-card'
    tipNode.dataset.open = 'false'
    tipNode.setAttribute('role', 'tooltip')
    document.body.appendChild(tipNode)
  }
  return tipNode
}

function placeTip() {
  if (!tipNode || !tipTarget) return
  const gap = 10
  const anchor = tipTarget.getBoundingClientRect()
  const card = tipNode.getBoundingClientRect()
  const maxLeft = Math.max(8, window.innerWidth - card.width - 8)
  const left = Math.min(Math.max(8, anchor.left + anchor.width / 2 - card.width / 2), maxLeft)
  let top = anchor.bottom + gap
  let placement = 'bottom'
  if (top + card.height > window.innerHeight - 8 && anchor.top - card.height - gap > 8) {
    top = anchor.top - card.height - gap
    placement = 'top'
  }
  tipNode.style.left = `${Math.round(left)}px`
  tipNode.style.top = `${Math.round(top)}px`
  tipNode.dataset.placement = placement
}

function showTip(el: HTMLElement) {
  const text = el.dataset.tip
  if (!text) return
  const card = tipCard()
  tipTarget = el
  card.textContent = text
  placeTip()
  card.dataset.open = 'true'
}

function hideTip() {
  clearTimeout(timer)
  timerOwner = null
  if (tipNode) tipNode.dataset.open = 'false'
  tipTarget = null
}

function scheduleTip(el: HTMLElement) {
  clearTimeout(timer)
  timerOwner = el
  timer = setTimeout(() => { if (timerOwner === el) showTip(el) }, TIP_DELAY)
}

const cleanups = new WeakMap<HTMLElement, () => void>()

const tipDirective: Directive<HTMLElement, string | undefined> = {
  mounted(el, binding) {
    el.dataset.tip = binding.value || ''
    const enter = (event: PointerEvent) => { if (event.pointerType === 'mouse') scheduleTip(el) }
    const leave = () => { if (timerOwner === el) clearTimeout(timer); if (tipTarget === el) hideTip() }
    const focus = () => { if (el.matches(':focus-visible')) showTip(el) }
    const blur = () => { if (tipTarget === el) hideTip() }
    const press = () => { if (tipTarget === el || timerOwner === el) hideTip() }
    el.addEventListener('pointerenter', enter)
    el.addEventListener('pointerleave', leave)
    el.addEventListener('focus', focus)
    el.addEventListener('blur', blur)
    el.addEventListener('pointerdown', press)
    cleanups.set(el, () => {
      el.removeEventListener('pointerenter', enter)
      el.removeEventListener('pointerleave', leave)
      el.removeEventListener('focus', focus)
      el.removeEventListener('blur', blur)
      el.removeEventListener('pointerdown', press)
      if (tipTarget === el || timerOwner === el) hideTip()
    })
  },
  updated(el, binding) {
    el.dataset.tip = binding.value || ''
    if (tipTarget === el && tipNode) { tipNode.textContent = el.dataset.tip; placeTip() }
  },
  unmounted(el) {
    cleanups.get(el)?.()
    cleanups.delete(el)
  },
}

window.addEventListener('scroll', () => { if (tipTarget) hideTip() }, true)
window.addEventListener('resize', () => { if (tipTarget) hideTip() })

createApp(App).directive('tip', tipDirective).mount('#app')
