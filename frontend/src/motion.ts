import { gsap } from 'gsap'
import { Flip } from 'gsap/Flip'

gsap.registerPlugin(Flip)

export { gsap, Flip }
export const motionDuration = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 0.28
