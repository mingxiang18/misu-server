import { useMediaQuery } from '@vueuse/core'

/**
 * 响应式断点 Hook
 *
 * 与 src/styles/tokens.css 中的 --bp-mobile-max / --bp-desktop-min 对齐。
 * 只有两档：
 *   - mobile  : ≤ 640px
 *   - desktop : ≥ 641px（平板复用桌面布局）
 *
 * 使用：
 *   const { isMobile, isDesktop } = useBreakpoint()
 *   <template v-if="isMobile">...</template>
 */
export function useBreakpoint () {
  const isMobile = useMediaQuery('(max-width: 640px)')
  const isDesktop = useMediaQuery('(min-width: 641px)')
  return { isMobile, isDesktop }
}
