import { ref, computed, watch } from 'vue'
import { useMediaQuery, useStorage } from '@vueuse/core'

/**
 * useTheme · 黑夜模式 (A · 月光紫蓝)
 *
 * 三态偏好 + 路由强制覆盖：
 *   - pref           localStorage 持久化的用户偏好：'light' | 'dark' | 'system'
 *   - routeOverride  当前路由的强制主题（仅 'dark' 或 null），由 router guard 设置
 *   - effective      实际生效主题，写到 <html> 的 .dark class
 *
 * 切换时通过 .theme-transitioning 类触发 200ms 颜色淡入（见 tokens.css）。
 * 首次挂载不带过渡，避免初始 dark 用户进站时闪一下 light。
 */

const STORAGE_KEY = 'misu-theme-pref'
const TRANSITION_CLASS = 'theme-transitioning'
const TRANSITION_MS = 250
const HINT_AUTO_DISMISS_MS = 8000

const pref = useStorage(STORAGE_KEY, 'system')
const routeOverride = ref(null)
const autoSwitchHint = ref(false)        // 进入 dark 路由时弹"已自动切换"角标
const systemDark = useMediaQuery('(prefers-color-scheme: dark)')

const effective = computed(() => {
  if (routeOverride.value === 'dark') return 'dark'
  if (pref.value === 'system') return systemDark.value ? 'dark' : 'light'
  return pref.value === 'dark' ? 'dark' : 'light'
})

let initialized = false
let transitionTimer = null
let hintTimer = null

function applyTheme (theme) {
  const html = document.documentElement
  if (initialized) {
    html.classList.add(TRANSITION_CLASS)
    if (transitionTimer) clearTimeout(transitionTimer)
    transitionTimer = window.setTimeout(() => {
      html.classList.remove(TRANSITION_CLASS)
      transitionTimer = null
    }, TRANSITION_MS)
  }
  html.classList.toggle('dark', theme === 'dark')
  initialized = true
}

function setHint (on) {
  autoSwitchHint.value = on
  if (hintTimer) {
    clearTimeout(hintTimer)
    hintTimer = null
  }
  if (on) {
    hintTimer = window.setTimeout(() => {
      autoSwitchHint.value = false
      hintTimer = null
    }, HINT_AUTO_DISMISS_MS)
  }
}

applyTheme(effective.value)
watch(effective, applyTheme)

export function useTheme () {
  return {
    pref,                                // 'light' | 'dark' | 'system'
    effective,                           // computed 'light' | 'dark'
    autoSwitchHint,                      // 'router 自动切到 dark' 的提示 ref（ThemeSwitcher 监听）
    isSystemForced: computed(() => routeOverride.value !== null),
    setPref (value) {
      if (value === 'light' || value === 'dark' || value === 'system') {
        pref.value = value
        // 用户主动选择主题 → 撤销自动切换覆盖，并清掉提示
        routeOverride.value = null
        setHint(false)
      }
    },
    setRouteOverride (theme) {
      const newOverride = theme === 'dark' ? 'dark' : null
      // 进入 dark 路由前，effective 是不是已经是 dark？
      const wasAlreadyDark = effective.value === 'dark'
      routeOverride.value = newOverride
      if (newOverride === 'dark' && !wasAlreadyDark) {
        // 真正发生了"自动切换" → 提示
        setHint(true)
      } else if (newOverride === null) {
        // 离开 dark 路由 → 提示消失（无论 effective 怎么变）
        setHint(false)
      }
    },
    dismissHint () {
      setHint(false)
    },
  }
}
