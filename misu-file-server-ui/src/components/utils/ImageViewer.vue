<template>
  <div class="iv-mask" @click.self="onMaskClick" @wheel.prevent="onWheel">
    <!-- 顶部工具条 -->
    <div class="iv-toolbar" @click.stop>
      <span class="iv-counter" v-if="urlList.length > 1">{{ index + 1 }} / {{ urlList.length }}</span>
      <span class="iv-spacer"></span>
      <button class="iv-tool" type="button" aria-label="缩小" @click="zoomBy(-0.4)"><ZoomOut /></button>
      <button class="iv-tool" type="button" aria-label="放大" @click="zoomBy(0.4)"><ZoomIn /></button>
      <button class="iv-tool" type="button" aria-label="旋转" @click="rotate"><RefreshRight /></button>
      <button class="iv-tool" type="button" aria-label="关闭" @click="emitClose"><Close /></button>
    </div>

    <!-- 滑动轨道：translateX = -index*100% + dragX -->
    <div
        class="iv-track"
        :class="{ 'iv-animating': animating }"
        :style="trackStyle"
        @touchstart.passive="onTouchStart"
        @touchmove.prevent="onTouchMove"
        @touchend="onTouchEnd"
        @mousedown="onMouseDown">
      <div class="iv-slide" v-for="(url, i) in urlList" :key="i">
        <img
            v-if="Math.abs(i - index) <= 1"
            class="iv-img"
            :class="{ 'iv-grab': i === index && scale > 1 }"
            :src="url"
            :style="i === index ? imgStyle : null"
            draggable="false"
            alt=""
            @dblclick="toggleZoom"
            @load="i === index && onImgLoad($event)" />
      </div>
    </div>

    <!-- 桌面端左右箭头 -->
    <button v-if="urlList.length > 1" class="iv-arrow iv-prev" type="button" aria-label="上一张" @click.stop="prev"><ArrowLeft /></button>
    <button v-if="urlList.length > 1" class="iv-arrow iv-next" type="button" aria-label="下一张" @click.stop="next"><ArrowRight /></button>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { ArrowLeft, ArrowRight, Close, ZoomIn, ZoomOut, RefreshRight } from '@element-plus/icons-vue'

const props = defineProps({
  urlList: { type: Array, default: () => [] },
  initialIndex: { type: Number, default: 0 },
})
const emit = defineEmits(['close', 'switch'])

const index = ref(props.initialIndex || 0)
const scale = ref(1)
const rotateDeg = ref(0)
const translate = reactive({ x: 0, y: 0 })
const dragX = ref(0)          // 横向切换拖动偏移（px）
const animating = ref(true)   // 是否启用过渡
const MAX_SCALE = 6
const MIN_SCALE = 1

const trackStyle = computed(() => ({
  transform: `translateX(calc(${-index.value * 100}% + ${dragX.value}px))`,
}))
const imgStyle = computed(() => ({
  transform: `translate(${translate.x}px, ${translate.y}px) scale(${scale.value}) rotate(${rotateDeg.value}deg)`,
}))

const resetTransform = () => {
  scale.value = 1
  translate.x = 0
  translate.y = 0
  rotateDeg.value = 0
}

watch(index, (i) => {
  resetTransform()
  emit('switch', i)
}, { immediate: true })

const clampIndex = (i) => Math.max(0, Math.min(props.urlList.length - 1, i))

const goTo = (i) => {
  const ni = clampIndex(i)
  if (ni === index.value) { dragX.value = 0; return }
  index.value = ni
  dragX.value = 0
}
const prev = () => goTo(index.value - 1)
const next = () => goTo(index.value + 1)

const emitClose = () => emit('close')
const onMaskClick = () => { if (scale.value <= 1) emitClose() }

// ---------- 缩放 ----------
const zoomBy = (delta) => {
  const ns = Math.min(MAX_SCALE, Math.max(MIN_SCALE, +(scale.value + delta).toFixed(2)))
  scale.value = ns
  if (ns <= 1) { translate.x = 0; translate.y = 0 }
  clampPan()
}
const onWheel = (e) => { zoomBy(e.deltaY < 0 ? 0.3 : -0.3) }
const rotate = () => { rotateDeg.value = (rotateDeg.value + 90) % 360 }
const toggleZoom = () => {
  if (scale.value > 1) { resetTransform() }
  else { scale.value = 2.5 }
}

// 平移边界（依据当前图片渲染尺寸粗略钳制）
let imgEl = null
const onImgLoad = (e) => { imgEl = e.target }
const clampPan = () => {
  if (!imgEl || scale.value <= 1) { translate.x = 0; translate.y = 0; return }
  const maxX = (imgEl.clientWidth * (scale.value - 1)) / 2
  const maxY = (imgEl.clientHeight * (scale.value - 1)) / 2
  translate.x = Math.max(-maxX, Math.min(maxX, translate.x))
  translate.y = Math.max(-maxY, Math.min(maxY, translate.y))
}

// ---------- 触摸手势 ----------
const SWIPE_RATIO = 0.18   // 超过容器宽度比例则切换
let startX = 0, startY = 0, startTx = 0, startTy = 0
let mode = null            // 'swipe' | 'pan' | 'pinch'
let pinchStartDist = 0, pinchStartScale = 1
let lastTap = 0

const dist = (t) => {
  const dx = t[0].clientX - t[1].clientX
  const dy = t[0].clientY - t[1].clientY
  return Math.hypot(dx, dy)
}

const onTouchStart = (e) => {
  animating.value = false
  if (e.touches.length === 2) {
    mode = 'pinch'
    pinchStartDist = dist(e.touches)
    pinchStartScale = scale.value
    return
  }
  const t = e.touches[0]
  startX = t.clientX; startY = t.clientY
  startTx = translate.x; startTy = translate.y
  mode = scale.value > 1 ? 'pan' : 'swipe'
  // 双击检测
  const now = Date.now()
  if (now - lastTap < 280) { toggleZoom(); mode = null }
  lastTap = now
}

const onTouchMove = (e) => {
  if (mode === 'pinch' && e.touches.length === 2) {
    const ns = Math.min(MAX_SCALE, Math.max(MIN_SCALE, pinchStartScale * (dist(e.touches) / pinchStartDist)))
    scale.value = +ns.toFixed(3)
    if (ns <= 1) { translate.x = 0; translate.y = 0 }
    return
  }
  if (!mode) return
  const t = e.touches[0]
  const dx = t.clientX - startX
  const dy = t.clientY - startY
  if (mode === 'pan') {
    translate.x = startTx + dx
    translate.y = startTy + dy
    clampPan()
  } else if (mode === 'swipe') {
    // 边缘阻尼
    let d = dx
    if ((index.value === 0 && d > 0) || (index.value === props.urlList.length - 1 && d < 0)) d *= 0.35
    dragX.value = d
  }
}

const onTouchEnd = (e) => {
  animating.value = true
  if (mode === 'swipe') {
    const threshold = (imgEl ? imgEl.clientWidth : window.innerWidth) * SWIPE_RATIO
    if (dragX.value <= -threshold) next()
    else if (dragX.value >= threshold) prev()
    else dragX.value = 0
  }
  if (mode === 'pinch' && scale.value <= 1) resetTransform()
  if (e.touches.length === 0) mode = null
}

// ---------- 鼠标拖动：未放大时左右拖切图，放大后拖动平移 ----------
let mouseDown = false, mouseMode = null, mStartX = 0, mStartY = 0, mTx = 0, mTy = 0
const onMouseDown = (e) => {
  mouseDown = true
  animating.value = false
  mStartX = e.clientX; mStartY = e.clientY
  mTx = translate.x; mTy = translate.y
  mouseMode = scale.value > 1 ? 'pan' : 'swipe'
  e.preventDefault()
}
const onMouseMove = (e) => {
  if (!mouseDown) return
  if (mouseMode === 'pan') {
    translate.x = mTx + (e.clientX - mStartX)
    translate.y = mTy + (e.clientY - mStartY)
    clampPan()
  } else if (mouseMode === 'swipe') {
    let d = e.clientX - mStartX
    if ((index.value === 0 && d > 0) || (index.value === props.urlList.length - 1 && d < 0)) d *= 0.35
    dragX.value = d
  }
}
const onMouseUp = () => {
  if (!mouseDown) return
  mouseDown = false
  animating.value = true
  if (mouseMode === 'swipe') {
    const threshold = (imgEl ? imgEl.clientWidth : window.innerWidth) * SWIPE_RATIO
    if (dragX.value <= -threshold) next()
    else if (dragX.value >= threshold) prev()
    else dragX.value = 0
  }
  mouseMode = null
}

// ---------- 键盘 ----------
const onKey = (e) => {
  if (e.key === 'ArrowLeft') prev()
  else if (e.key === 'ArrowRight') next()
  else if (e.key === 'Escape') emitClose()
  else if (e.key === '+' || e.key === '=') zoomBy(0.4)
  else if (e.key === '-') zoomBy(-0.4)
}

onMounted(() => {
  document.addEventListener('keydown', onKey)
  window.addEventListener('mousemove', onMouseMove)
  window.addEventListener('mouseup', onMouseUp)
  document.body.style.overflow = 'hidden'
})
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKey)
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('mouseup', onMouseUp)
  document.body.style.overflow = ''
})
</script>

<style scoped>
.iv-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  background: rgba(0, 0, 0, 0.9);
  overflow: hidden;
  touch-action: none;
  user-select: none;
}
.iv-track {
  position: absolute;
  inset: 0;
  display: flex;
  will-change: transform;
}
.iv-track.iv-animating {
  transition: transform 0.28s cubic-bezier(0.25, 0.8, 0.25, 1);
}
.iv-slide {
  flex: 0 0 100%;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.iv-img {
  max-width: 92vw;
  max-height: 88vh;
  object-fit: contain;
  will-change: transform;
  -webkit-user-drag: none;
}
.iv-img { cursor: grab; }
.iv-img.iv-grab { cursor: grab; }

.iv-toolbar {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  z-index: 3;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: linear-gradient(to bottom, rgba(0, 0, 0, 0.45), transparent);
}
.iv-counter { color: #fff; font-size: 14px; opacity: 0.9; }
.iv-spacer { flex: 1; }
.iv-tool {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
  cursor: pointer;
  transition: background 0.15s;
}
.iv-tool:hover { background: rgba(255, 255, 255, 0.25); }
.iv-tool :deep(svg) { width: 20px; height: 20px; }

.iv-arrow {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  z-index: 3;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border: none;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.14);
  color: #fff;
  cursor: pointer;
  transition: background 0.15s;
}
.iv-arrow:hover { background: rgba(255, 255, 255, 0.3); }
.iv-arrow :deep(svg) { width: 22px; height: 22px; }
.iv-prev { left: 20px; }
.iv-next { right: 20px; }

/* 移动端隐藏左右箭头，靠滑动 */
@media (max-width: 640px) {
  .iv-arrow { display: none; }
  .iv-img { max-width: 100vw; max-height: 84vh; }
}
</style>
