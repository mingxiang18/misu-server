
<template>
  <div class="fullscreen-overlay" @click.self="onOverlayClick">
    <div class="fullscreen-content">
      <video :src="videoUrl"
             ref="videoRef"
             controls
             preload="metadata"
             width="100%"
             height="auto"/>
    </div>
  </div>

</template>

<script setup>
import { onMounted, onBeforeUnmount, ref } from "vue";
import flvjs from "flv.js";

// 接收外部传入的接口函数
const props = defineProps({
  videoUrl: {
    type: String,
    required: true,
  },
  videoType: {
    type: String,
    required: true,
  }
});


// 声明自定义事件 `close`
const emit = defineEmits(['close']);

// 引用 video 元素
const videoRef = ref(null);

let flvPlayer = null;

onMounted(() => {
  if (props.videoType === 'flv') {
    if (flvjs.isSupported()) {
      flvPlayer = flvjs.createPlayer({
        type: "flv",
        url: props.videoUrl,
      });

      flvPlayer.attachMediaElement(videoRef.value);
      flvPlayer.load();
      flvPlayer.play().catch((error) => {
        console.error("Error playing FLV stream:", error);
      });
    } else {
      console.error("FLV.js is not supported in this browser.");
    }
  }
});

onBeforeUnmount(() => {
  if (flvPlayer) {
    flvPlayer.destroy();
    flvPlayer = null;
  }
});

// 点击遮罩触发关闭事件
const onOverlayClick = () => {
  videoRef.value.pause(); // 停止播放
  videoRef.value.src = ''; // 清空视频资源
  videoRef.value.load();   // 释放资源
  emit('close');
};

// 控制组件的显示和隐藏
const visible = ref(true);

// 暴露显示和隐藏的方法
const show = () => {
  visible.value = true;
};

const hide = () => {
  visible.value = false;
};

defineExpose({
  show,
  hide
});

</script>

<style scoped>
.fullscreen-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100svh;
  background-color: rgba(0, 0, 0, 0.7);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 9999; /* 保证它在最上层 */
}

.fullscreen-content {
  max-width: 90%; /* 限制内容宽度 */
  max-height: 90%; /* 限制内容高度 */
}

</style>
