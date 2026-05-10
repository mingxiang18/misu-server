import { ref, computed, onMounted, onBeforeUnmount } from 'vue'

const isOnline = ref(typeof navigator !== 'undefined' ? navigator.onLine !== false : true)
const backendDownAt = ref(null)
const RECOVERY_DELAY_MS = 2000

let listenersBound = false
function onOnline() { isOnline.value = true }
function onOffline() { isOnline.value = false }

export function markBackendDown() {
  backendDownAt.value = Date.now()
}

export function markBackendUp() {
  if (backendDownAt.value) {
    setTimeout(() => { backendDownAt.value = null }, RECOVERY_DELAY_MS)
  }
}

export function useNetwork() {
  const isBackendDown = computed(() => backendDownAt.value !== null)
  const isOffline = computed(() => !isOnline.value)
  const showBanner = computed(() => isOffline.value || isBackendDown.value)

  onMounted(() => {
    if (listenersBound) return
    window.addEventListener('online', onOnline)
    window.addEventListener('offline', onOffline)
    listenersBound = true
  })

  onBeforeUnmount(() => {
    if (!listenersBound) return
    window.removeEventListener('online', onOnline)
    window.removeEventListener('offline', onOffline)
    listenersBound = false
  })

  return { isOnline, isOffline, isBackendDown, showBanner, markBackendDown, markBackendUp }
}
