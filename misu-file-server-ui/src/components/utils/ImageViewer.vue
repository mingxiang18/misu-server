<template>
  <div ref="rootRef" class="iv-mask" @click.self="onMaskClick" @wheel.prevent="onWheel">
    <!-- 顶部工具条 -->
    <div class="iv-toolbar" @click.stop>
      <span class="iv-counter" v-if="urlList.length > 1">{{ index + 1 }} / {{ urlList.length }}</span>
      <span class="iv-spacer"></span>
      <button class="iv-tool" type="button" aria-label="缩小" @click="zoomBy(-0.5)"><ZoomOut /></button>
      <button class="iv-tool" type="button" aria-label="放大" @click="zoomBy(0.5)"><ZoomIn /></button>
      <button class="iv-tool" type="button" aria-label="旋转" @click="rotate"><RefreshRight /></button>
      <button class="iv-tool" type="button" aria-label="关闭" @click="emitClose"><Close /></button>
    </div>

    <!-- 滑动轨道：translateX = -index*100% + dragX -->
    <div
        class="iv-track"
        :class="{ 'iv-animating': trackAnimating }"
        :style="trackStyle"
        @touchstart.passive="onTouchStart"
        @touchmove.prevent="onTouchMove"
        @touchend="onTouchEnd"
        @mousedown="onMouseDown">
      <div class="iv-slide" v-for="(url, i) in urlList" :key="i">
        <img
            v-if="Math.abs(i - index) <= 1"
            class="iv-img"
            :class="{ 'iv-anim': i === index && imgAnimating, 'iv-grabbing': i === index && scale > 1 }"
            :src="url"
            :style="i === index ? imgStyle : null"
            draggable="false"
            alt="" />
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
const dragX = ref(0)            // 横向切换拖动偏移（px）
const trackAnimating = ref(true)   // 轨道（切图）过渡
const imgAnimating = ref(false)    // 图片（缩放/平移）过渡 —— 实时手势时关闭，程序变换时打开
const MAX_SCALE = 6
const MIN_SCALE = 1
const SWIPE_RATIO = 0.18           // 横滑超过容器宽度比例则切图
const MOVE_THRESH = 8              // 超过该位移视为「拖动」而非「点击」
const DOUBLE_TAP_MS = 280

const trackStyle = computed(() => ({
  transform: `translateX(calc(${-index.value * 100}% + ${dragX.value}px))`,
}))
const imgStyle = computed(() => ({
  transform: `translate(${translate.x}px, ${translate.y}px) scale(${scale.value}) rotate(${rotateDeg.value}deg)`,
}))

const rootRef = ref(null)
// 实时取当前图片元素（不依赖 @load，规避缓存图不触发 load 的问题）
const curImg = () => rootRef.value && rootRef.value.querySelector('.iv-slide:nth-child(' + (index.value + 1) + ') .iv-img')
const curImgWidth = () => { const el = curImg(); return el ? el.clientWidth : window.innerWidth }

// 平移边界钳制（基于图片渲染尺寸）
const clampPan = () => {
  const el = curImg()
  if (!el || scale.value <= 1) { translate.x = 0; translate.y = 0; return }
  const maxX = (el.clientWidth * (scale.value - 1)) / 2
  const maxY = (el.clientHeight * (scale.value - 1)) / 2
  translate.x = Math.max(-maxX, Math.min(maxX, translate.x))
  translate.y = Math.max(-maxY, Math.min(maxY, translate.y))
}

// 复位到适应屏幕（保留旋转角）
const fitReset = (animated = false) => {
  imgAnimating.value = animated
  scale.value = 1
  translate.x = 0
  translate.y = 0
}
// 切图时整体复位（含旋转，瞬时）
const fullReset = () => {
  imgAnimating.value = false
  scale.value = 1
  translate.x = 0
  translate.y = 0
  rotateDeg.value = 0
}

watch(index, (i) => { fullReset(); emit('switch', i) }, { immediate: true })

const clampIndex = (i) => Math.max(0, Math.min(props.urlList.length - 1, i))
const goTo = (i) => {
  const ni = clampIndex(i)
  dragX.value = 0
  if (ni !== index.value) index.value = ni
}
const prev = () => goTo(index.value - 1)
const next = () => goTo(index.value + 1)

const emitClose = () => emit('close')
const onMaskClick = () => { if (scale.value <= 1) emitClose() }

// ---------- 缩放 ----------
// 朝指定屏幕坐标 (cx,cy) 缩放到 target；不传坐标则以图片中心缩放
const zoomTo = (target, cx, cy, animated = true) => {
  const ns = Math.min(MAX_SCALE, Math.max(MIN_SCALE, +target.toFixed(3)))
  imgAnimating.value = animated
  if (ns <= 1) { scale.value = 1; translate.x = 0; translate.y = 0; return }
  const el = curImg()
  if (el && cx != null) {
    const r = el.getBoundingClientRect()
    const ox = r.left + r.width / 2
    const oy = r.top + r.height / 2
    const sOld = scale.value
    // 锚点在「原始图片坐标系」中的位置
    const px = (cx - ox - translate.x) / sOld
    const py = (cy - oy - translate.y) / sOld
    scale.value = ns
    translate.x = cx - ox - px * ns
    translate.y = cy - oy - py * ns
  } else {
    scale.value = ns
  }
  clampPan()
}
const zoomBy = (delta) => zoomTo(scale.value + delta, null, null, true)
const onWheel = (e) => zoomTo(scale.value + (e.deltaY < 0 ? 0.3 : -0.3), e.clientX, e.clientY, false)
const rotate = () => { imgAnimating.value = true; rotateDeg.value = (rotateDeg.value + 90) % 360 }

// ---------- 单击 / 双击 ----------
let tapTimer = null
const handleTap = (cx, cy) => {
  if (tapTimer) {            // 第二次 → 双击
    clearTimeout(tapTimer); tapTimer = null
    if (scale.value > 1) fitReset(true)
    else zoomTo(2.5, cx, cy, true)
  } else {                   // 等待是否双击；否则单击
    tapTimer = setTimeout(() => {
      tapTimer = null
      if (scale.value > 1) fitReset(true)   // 放大态：单击先复位
      else emitClose()                       // 适应态：单击关闭
    }, DOUBLE_TAP_MS)
  }
}

// ---------- 触摸手势 ----------
let startX = 0, startY = 0, startTx = 0, startTy = 0, moved = false
let gesture = null                 // 'swipe' | 'pan' | 'pinch'
let pinchStartDist = 0, pinchStartScale = 1

const dist = (t) => Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY)

const onTouchStart = (e) => {
  imgAnimating.value = false
  if (e.touches.length === 2) {
    gesture = 'pinch'
    moved = true
    pinchStartDist = dist(e.touches)
    pinchStartScale = scale.value
    if (tapTimer) { clearTimeout(tapTimer); tapTimer = null }
    return
  }
  const t = e.touches[0]
  startX = t.clientX; startY = t.clientY
  startTx = translate.x; startTy = translate.y
  moved = false
  gesture = scale.value > 1 ? 'pan' : 'swipe'
}

const onTouchMove = (e) => {
  if (gesture === 'pinch' && e.touches.length === 2) {
    zoomTo(pinchStartScale * (dist(e.touches) / pinchStartDist), null, null, false)
    return
  }
  if (!gesture) return
  const t = e.touches[0]
  const dx = t.clientX - startX
  const dy = t.clientY - startY
  if (Math.abs(dx) > MOVE_THRESH || Math.abs(dy) > MOVE_THRESH) moved = true
  if (gesture === 'pan') {
    imgAnimating.value = false
    translate.x = startTx + dx
    translate.y = startTy + dy
    clampPan()
  } else if (gesture === 'swipe') {
    let d = dx
    if ((index.value === 0 && d > 0) || (index.value === props.urlList.length - 1 && d < 0)) d *= 0.35
    dragX.value = d
  }
}

const onTouchEnd = (e) => {
  if (gesture === 'swipe') {
    trackAnimating.value = true
    if (moved) {
      const threshold = curImgWidth() * SWIPE_RATIO
      if (dragX.value <= -threshold) next()
      else if (dragX.value >= threshold) prev()
      else dragX.value = 0
    } else {
      dragX.value = 0
      handleTap(startX, startY)         // 未移动 → 点击
    }
  } else if (gesture === 'pan') {
    if (!moved) handleTap(startX, startY)
  } else if (gesture === 'pinch') {
    if (scale.value <= 1) fitReset(true)
    else clampPan()
  }
  if (e.touches.length === 0) gesture = null
}

// ---------- 鼠标（桌面）----------
let mouseDown = false, mouseMode = null, mStartX = 0, mStartY = 0, mTx = 0, mTy = 0, mMoved = false
const onMouseDown = (e) => {
  mouseDown = true
  imgAnimating.value = false
  mStartX = e.clientX; mStartY = e.clientY
  mTx = translate.x; mTy = translate.y
  mMoved = false
  mouseMode = scale.value > 1 ? 'pan' : 'swipe'
  e.preventDefault()
}
const onMouseMove = (e) => {
  if (!mouseDown) return
  const dx = e.clientX - mStartX
  const dy = e.clientY - mStartY
  if (Math.abs(dx) > MOVE_THRESH || Math.abs(dy) > MOVE_THRESH) mMoved = true
  if (mouseMode === 'pan') {
    translate.x = mTx + dx
    translate.y = mTy + dy
    clampPan()
  } else if (mouseMode === 'swipe') {
    let d = dx
    if ((index.value === 0 && d > 0) || (index.value === props.urlList.length - 1 && d < 0)) d *= 0.35
    dragX.value = d
  }
}
const onMouseUp = (e) => {
  if (!mouseDown) return
  mouseDown = false
  if (mouseMode === 'swipe') {
    trackAnimating.value = true
    if (mMoved) {
      const threshold = curImgWidth() * SWIPE_RATIO
      if (dragX.value <= -threshold) next()
      else if (dragX.value >= threshold) prev()
      else dragX.value = 0
    } else {
      dragX.value = 0
      handleTap(e.clientX, e.clientY)
    }
  } else if (mouseMode === 'pan' && !mMoved) {
    handleTap(e.clientX, e.clientY)
  }
  mouseMode = null
}

// ---------- 键盘 ----------
const onKey = (e) => {
  if (e.key === 'ArrowLeft') prev()
  else if (e.key === 'ArrowRight') next()
  else if (e.key === 'Escape') emitClose()
  else if (e.key === '+' || e.key === '=') zoomBy(0.5)
  else if (e.key === '-') zoomBy(-0.5)
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
  if (tapTimer) clearTimeout(tapTimer)
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
  transition: transform 0.3s cubic-bezier(0.22, 0.61, 0.36, 1);
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
  cursor: grab;
}
/* 缩放/平移过渡（实时手势时不加该 class，保证跟手） */
.iv-img.iv-anim {
  transition: transform 0.28s cubic-bezier(0.22, 0.61, 0.36, 1);
}
.iv-img.iv-grabbing { cursor: grab; }

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
