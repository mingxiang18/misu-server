<template>
  <div class="video-room">
    <!-- Notice strip -->
    <div class="video-notice">
      <el-carousel
          height="28px"
          direction="vertical"
          :autoplay="noticeList.length > 1"
          :interval="5000"
          arrow="never"
          indicator-position="none">
        <el-carousel-item v-for="item in noticeList" :key="item">
          <span class="video-notice-text">{{ item }}</span>
        </el-carousel-item>
      </el-carousel>
    </div>

    <!-- Player -->
    <div class="video-frame">
      <video
          ref="videoRef"
          :src="videoRoom.videoPath"
          class="video-player"
          controls
          preload="metadata"
          webkit-playsinline="true"
          playsinline="true"
          @play="updateVideoProgress"
          @pause="updateVideoProgress"
          @seeked="updateVideoProgress"/>
    </div>

    <!-- Action row (horizontally scrollable on mobile) -->
    <div class="video-actions">
      <div class="video-actions-inner">
        <span class="video-chip" :data-tone="syncModeTagType">同步：{{ syncModeText }}</span>
        <button class="video-action" type="button" @click="openViewerVisible">
          <el-icon><User /></el-icon>
          <span>{{ roomViewerList.length }}</span>
        </button>
        <button class="video-action" type="button" @click="openVideoListVisible()">
          <el-icon><List /></el-icon>
          <span>播放列表</span>
        </button>
        <button class="video-action" type="button" @click="getRoomShareUrl">
          <el-icon><Share /></el-icon>
          <span>分享</span>
        </button>
        <button
            v-if="videoRoom.creatorFlag === false"
            class="video-action danger"
            type="button"
            @click="quitVideoRoom">
          <el-icon><SwitchButton /></el-icon>
          <span>退出</span>
        </button>
        <button
            v-if="videoRoom.creatorFlag === true"
            class="video-action danger"
            type="button"
            @click="closeVideoRoom">
          <el-icon><SwitchButton /></el-icon>
          <span>结束放映</span>
        </button>
      </div>
    </div>

    <!-- Comments -->
    <div class="video-comments">
      <div v-if="commentList.length > 0" ref="commentListRef" class="comment-list">
        <div
            v-for="comment in commentList"
            :key="comment.createTime + comment.userName + comment.content"
            class="comment-item">
          <div class="comment-meta">
            <span class="comment-user">{{ comment.userName }}</span>
            <span class="comment-time">{{ comment.createTime }}</span>
          </div>
          <p class="comment-content">{{ comment.content }}</p>
        </div>
      </div>
      <div v-else class="comment-empty">
        <el-icon class="comment-empty-icon"><ChatLineSquare /></el-icon>
        <p>还没人开口…</p>
      </div>

      <div class="comment-input-row">
        <el-input
            v-model="commentsInput"
            placeholder="输入评论"
            maxlength="500"
            show-word-limit
            @keyup.enter="sendRoomComment"/>
        <el-button
            type="primary"
            :loading="commentSending"
            :disabled="!commentsInput || !commentsInput.trim()"
            @click="sendRoomComment">
          发送
        </el-button>
      </div>
    </div>

    <el-dialog
        v-model="viewerVisible"
        :width="dialogWidth"
        title="浏览者列表">
      <el-table :data="roomViewerList">
        <el-table-column type="index"/>
        <el-table-column prop="userName" label="用户名"/>
        <el-table-column prop="syncTime" label="最后在线" min-width="160"/>
      </el-table>
    </el-dialog>

    <el-dialog
        v-model="videoListVisible"
        :width="wideDialogWidth"
        title="视频播放列表">
      <el-table :data="videoList" v-loading="videoListLoading" max-height="70svh">
        <el-table-column prop="fileName" label="视频名称">
          <template #default="scope">
            <div class="table-text">
              <span style="word-break: break-all;">{{ scope.row.fileName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="scope">
            <div class="table-option">
              <div v-if="scope.row.fileName !== videoRoom.roomName">
                <el-button link type="primary" @click="playVideo(scope.row)">播放</el-button>
              </div>
              <div v-if="scope.row.fileName === videoRoom.roomName">
                <el-text type="info">当前播放</el-text>
              </div>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute } from 'vue-router';
import { useBreakpoint } from '@/composables/useBreakpoint';
import {
  getVideoRoomFromId,
  getVideoState,
  getRoomMembers,
  sendComment as sendCommentApi,
  getComments,
  getVideoRoomShareUrl,
  updateVideoState,
  quitVideoRoom as quitVideoRoomApi,
  closeVideoRoom as closeVideoRoomApi,
  getMyActiveRoom,
  playMyVideo
} from '@/api/fileServer/videoRoom';
import { getFileList as getFileListApi} from '@/api/fileServer/fileServer';
import { Share, User, List, SwitchButton, ChatLineSquare } from "@element-plus/icons-vue";
import { ElMessage, ElMessageBox} from "element-plus";
import router from "@/router";
import { getToken } from "@/api/auth/token";

// 响应式断点 + 弹层宽度
const { isMobile } = useBreakpoint();
const dialogWidth = computed(() => isMobile.value ? 'calc(100vw - 32px)' : '520px');
const wideDialogWidth = computed(() => isMobile.value ? 'calc(100vw - 32px)' : '760px');

// 引用 video 元素
const videoRef = ref(null);
const videoRoom = ref({
  roomId: '',
  roomName: '',
  directoryPath: '',
  directoryOpenFlag: null,
  videoPath: '',
  playTime: '',
  creatorId: '',
  creatorFlag: false,
});

// 在url中获取id
const route = useRoute();
const roomId = route.params.roomId;

const videoCanPlay = ref(false);

const noticeList = ref(['通知']);
const commentsInput = ref('');
const commentList = ref([]);
const commentSending = ref(false);
const commentListRef = ref(null);
const syncMode = ref('connecting');
const syncModeText = ref('连接中');
const syncModeTagType = ref('warning');

//观看人员界面
const viewerVisible = ref(false);
const roomViewerList = ref([]);

//视频列表界面
const videoListVisible = ref(false);
const videoList = ref([]);
const videoListLoading = ref(false);

let viewerTimer = null;
let creatorTimer = null;
let commentTimer = null;
let videoRoomSocket = null;
let reconnectTimer = null;
let reconnectCount = 0;

const videoNotFoundNoticeFlag = ref(false);

// 获取放映室信息
const getVideoRoomMessage = () => {
  if (!roomId) {
    getMyActiveRoom().then((response) => {
      if (response.data && response.data.roomId) {
        router.replace(`/fileServer/videoRoom/${response.data.roomId}`);
        return;
      }
      videoRoomNotFoundHandle();
    }).catch(() => {
      videoRoomNotFoundHandle();
    });
    return;
  }

  getVideoRoomFromId(roomId).then((response) => {
    // 监听canplay事件
    videoRef.value.addEventListener('canplay', handleCanPlay);

    // 监听error事件
    videoRef.value.addEventListener('error', handleError);

    videoRoom.value = response.data;
    videoRoom.value.videoPath = normalizeResourceUrl(videoRoom.value.videoPath);
    videoRoom.value.state = 'pause';
    videoRoom.value.videoTime = '00:00:00';
    noticeList.value = [videoRoom.value.roomName]
  }).catch((error) => {
    if (!!error && !!error.code && error.code === 404) {
      videoRoomNotFoundHandle();
    }
  })
}

// 当视频准备好播放时设置状态
const handleCanPlay = () => {
  videoCanPlay.value = true;
};

// 视频播放错误
const handleError = () => {
  videoCanPlay.value = false;
  ElMessageBox.prompt(
      '当前放映室视频无法播放，可尝试刷新页面或者回到文件目录重新选择视频',
      '放映室视频无法播放',
      {
        confirmButtonText: '回到文件目录',
        showCancelButton: false,
      }
  ).then(() => {
    router.push('/fileServer/publicDirectory');
  }).catch(() => {
    router.push('/fileServer/publicDirectory');
  })
};

const openViewerVisible = () => {
  viewerVisible.value = true;
  if (!isSocketOpen()) {
    refreshRoomMembers();
  }
};

const refreshRoomMembers = () => {
  if (!roomId) {
    return;
  }
  getRoomMembers(roomId).then((response) => {
    roomViewerList.value = response.data;
  });
};

const refreshComments = () => {
  if (!roomId) {
    return;
  }
  getComments(roomId).then((response) => {
    const oldLength = commentList.value.length;
    commentList.value = response.data || [];
    if (commentList.value.length > oldLength) {
      scrollCommentToBottom();
    }
  });
};

const scrollCommentToBottom = () => {
  // 用 nextTick + requestAnimationFrame 双保险：
  // - nextTick 等 Vue 完成本次 DOM patch（v-for 新增节点）
  // - rAF 再让浏览器跑一帧 layout，scrollHeight 才是真实高度
  nextTick(() => {
    requestAnimationFrame(() => {
      const listEl = commentListRef.value;
      if (listEl) {
        listEl.scrollTop = listEl.scrollHeight;
      }
    });
  });
};

// 任意路径新增评论（HTTP / WS / 自己发送）都会改变 commentList 长度，
// 在这里统一触发滚到底，避免漏点。
watch(() => commentList.value.length, () => {
  scrollCommentToBottom();
});

const sendRoomComment = () => {
  const content = commentsInput.value.trim();
  if (!content) {
    return;
  }
  commentSending.value = true;
  if (isSocketOpen()) {
    videoRoomSocket.send(JSON.stringify({
      type: 'COMMENT',
      content: content,
      clientSendTime: Date.now(),
    }));
    commentsInput.value = '';
    commentSending.value = false;
    return;
  }
  sendCommentApi({
    roomId: roomId,
    content: content,
  }).then(() => {
    commentsInput.value = '';
    refreshComments();
    refreshRoomMembers();
  }).finally(() => {
    commentSending.value = false;
  });
};

const connectVideoRoomSocket = () => {
  if (!roomId || !getToken()) {
    setSyncMode('http');
    return;
  }
  closeVideoRoomSocket();
  setSyncMode('connecting');
  const wsUrl = buildVideoRoomSocketUrl();
  videoRoomSocket = new WebSocket(wsUrl);

  videoRoomSocket.onopen = () => {
    reconnectCount = 0;
    setSyncMode('websocket');
    videoRoomSocket.send(JSON.stringify({type: 'PING', clientSendTime: Date.now()}));
  };

  videoRoomSocket.onmessage = (event) => {
    handleSocketMessage(JSON.parse(event.data));
  };

  videoRoomSocket.onerror = () => {
    setSyncMode('error');
    console.warn('放映室WebSocket连接异常');
  };

  videoRoomSocket.onclose = () => {
    videoRoomSocket = null;
    if (!videoNotFoundNoticeFlag.value) {
      setSyncMode('http');
      scheduleReconnectVideoRoomSocket();
    }
  };
};

const buildVideoRoomSocketUrl = () => {
  const apiBase = import.meta.env.VITE_WS_API || import.meta.env.VITE_BASE_API || '';
  const baseUrl = apiBase.startsWith('http') ? apiBase : `${window.location.origin}${apiBase}`;
  const socketBaseUrl = baseUrl.replace(/^http:/, 'ws:').replace(/^https:/, 'wss:').replace(/\/$/, '');
  return `${socketBaseUrl}/fileServer/videoRoom/ws/${roomId}?token=${encodeURIComponent(getToken())}`;
};

const scheduleReconnectVideoRoomSocket = () => {
  clearTimeout(reconnectTimer);
  const reconnectDelay = Math.min(1000 * Math.pow(2, reconnectCount), 10000);
  reconnectCount += 1;
  reconnectTimer = setTimeout(() => {
    connectVideoRoomSocket();
  }, reconnectDelay);
};

const closeVideoRoomSocket = () => {
  clearTimeout(reconnectTimer);
  reconnectTimer = null;
  if (videoRoomSocket) {
    videoRoomSocket.onclose = null;
    videoRoomSocket.close();
    videoRoomSocket = null;
  }
};

const setSyncMode = (mode) => {
  syncMode.value = mode;
  if (mode === 'websocket') {
    syncModeText.value = 'WebSocket';
    syncModeTagType.value = 'success';
    return;
  }
  if (mode === 'http') {
    syncModeText.value = 'HTTP兜底';
    syncModeTagType.value = 'info';
    return;
  }
  if (mode === 'error') {
    syncModeText.value = '连接异常';
    syncModeTagType.value = 'danger';
    return;
  }
  syncModeText.value = '连接中';
  syncModeTagType.value = 'warning';
};

const isSocketOpen = () => {
  return videoRoomSocket && videoRoomSocket.readyState === WebSocket.OPEN;
};

const handleSocketMessage = (message) => {
  if (message.type === 'COMMENT') {
    appendComment(message.event);
    return;
  }
  if (message.type === 'PLAYBACK') {
    applyRemotePlaybackEvent(message.event);
    return;
  }
  if (message.type === 'MEMBER_LIST') {
    roomViewerList.value = (message.members || []).map((member) => ({
      userName: member.userName,
      syncTime: formatDateTime(member.syncTime),
    }));
    return;
  }
  if (message.type === 'ERROR') {
    ElMessage.error(message.message || '放映室同步失败');
  }
};

const appendComment = (event) => {
  if (!event) {
    return;
  }
  if (commentList.value.some((comment) => comment.eventId === event.eventId)) {
    return;
  }
  commentList.value = [...commentList.value, {
    eventId: event.eventId,
    userName: event.userName,
    content: event.content,
    createTime: event.createTime,
  }].slice(-100);
  scrollCommentToBottom();
};

const formatDateTime = (timestamp) => {
  if (!timestamp) {
    return '';
  }
  const date = new Date(timestamp);
  const pad = (value) => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
};

const applyRemotePlaybackEvent = (event) => {
  if (!event || videoRoom.value.creatorFlag === true) {
    return;
  }
  applyRoomSnapshot(event);
  if (videoCanPlay.value !== true) {
    return;
  }
  videoRoom.value.state = event.state;
  const networkDelaySeconds = event.state === 'play'
      ? Math.max(0, (Date.now() - event.serverReceiveTime) / 1000)
      : 0;
  const targetSeconds = Math.max(0, (event.videoTimeSeconds || 0) + networkDelaySeconds);
  const currentOffset = Math.abs(targetSeconds - videoRef.value.currentTime);

  if (currentOffset > 2 || (event.state === 'pause' && currentOffset > 0.75)) {
    videoRef.value.currentTime = targetSeconds;
  }

  if (event.state === 'play' && videoRef.value.paused) {
    videoRef.value.play();
  }
  if (event.state === 'pause' && !videoRef.value.paused) {
    videoRef.value.pause();
  }
};

const normalizeResourceUrl = (url) => {
  if (!url) {
    return '';
  }
  if (/^https?:\/\//i.test(url)) {
    return url;
  }
  const resourceBase = import.meta.env.VITE_RESOURCE_API || '';
  if (/^https?:\/\//i.test(resourceBase)) {
    try {
      const resourceUrl = new URL(resourceBase, window.location.origin);
      const currentHost = window.location.hostname;
      const isLocalPage = currentHost === 'localhost' || currentHost === '127.0.0.1';
      const isLocalResource = resourceUrl.hostname === 'localhost' || resourceUrl.hostname === '127.0.0.1';
      if (isLocalPage && isLocalResource && resourceUrl.hostname !== currentHost) {
        resourceUrl.hostname = currentHost;
      }
      return resourceUrl.toString().replace(/\/$/, '') + '/' + url.replace(/^\//, '');
    } catch (e) {
      return resourceBase.replace(/\/$/, '') + '/' + url.replace(/^\//, '');
    }
  }
  return url.startsWith('/') ? url : '/' + url;
};

const applyRoomSnapshot = (snapshot) => {
  if (!snapshot) {
    return;
  }
  const nextVideoPath = normalizeResourceUrl(snapshot.videoPath);
  if (nextVideoPath && videoRoom.value.videoPath !== nextVideoPath) {
    ElMessage({message: '房主切换了视频', type: 'info'});
    videoCanPlay.value = false;
    videoRoom.value.videoPath = nextVideoPath;
    nextTick(() => {
      videoRef.value?.load();
    });
  }
  videoRoom.value.roomName = snapshot.roomName || videoRoom.value.roomName;
  videoRoom.value.directoryPath = snapshot.directoryPath || videoRoom.value.directoryPath;
  videoRoom.value.directoryOpenFlag = snapshot.directoryOpenFlag ?? videoRoom.value.directoryOpenFlag;
  noticeList.value = [videoRoom.value.roomName];
};

// 在播放列表点击播放视频
const playVideo = (file) => {
  if (file.transcodeState === 'SUCCESS') {
    if (!file.transcodedStreamLink) {
      ElMessage.info('转码视频正在准备在线播放，请稍后刷新');
      return;
    }
    createVideoRoomFromFile(file, file.transcodedStreamLink);
    return;
  }
  if (file.transcodeState === 'TOO_LARGE') {
    ElMessage.warning(file.transcodeMessage || '视频过大，无法在线播放，可下载后观看');
    return;
  }
  if (file.transcodeState === 'FAILED' || file.transcodeState === 'UNSUPPORTED') {
    ElMessage.warning(file.transcodeMessage || '视频暂时无法在线播放');
    return;
  }
  if (!file.streamLink) {
    ElMessage.info('视频正在准备在线播放，请稍后刷新');
    return;
  }
  if (file.transcodeState === 'WAITING') {
    ElMessage.info('视频正在等待转码，本次播放原视频');
  } else if (file.transcodeState === 'PROCESSING') {
    ElMessage.info(`视频正在转码 ${file.transcodeProgress || 0}%，本次播放原视频`);
  }
  createVideoRoomFromFile(file, file.streamLink);
}

const createVideoRoomFromFile = (file, streamLink) => {
  if (!streamLink) {
    ElMessage.info('视频链接不存在，请稍后刷新');
    return;
  }
  videoListLoading.value = true;
  playMyVideo({
    roomName: file.fileName,
    directoryOpenFlag: videoRoom.value.directoryOpenFlag,
    filePath: `${file.filePath || ''}${file.fileName}`,
    preferTranscoded: file.transcodeState === 'SUCCESS',
  }).then((response) => {
    ElMessage.success('已切换放映视频');
    if (response.data.roomId === videoRoom.value.roomId) {
      videoListVisible.value = false;
      videoListLoading.value = false;
      applyRoomSnapshot(response.data);
      if (isSocketOpen()) {
        videoRoomSocket.send(JSON.stringify({
          type: 'PLAYBACK',
          state: 'pause',
          videoTimeSeconds: 0,
          clientSendTime: Date.now(),
        }));
      } else {
        getVideoStateMessage();
      }
      return;
    }
    window.location.href = `/fileServer/videoRoom/${response.data.roomId}`;
  }).catch(() => {
    videoListLoading.value = false;
    ElMessage.error('切换放映视频失败');
  });
}

//获取视频播放列表
const openVideoListVisible = () => {
  //显示列表弹窗
  videoList.value = [];
  videoListVisible.value = true;
  videoListLoading.value = true;
  const directoryPath = videoRoom.value.directoryPath || '/';
  getFileListApi(directoryPath, videoRoom.value.directoryOpenFlag).then((response) => {
    videoList.value = (response.data || [])
        .sort((a, b) => a.fileName.localeCompare(b.fileName))
        .filter((file) => file.fileType === "video");
  }).catch(() => {
    videoList.value = [];
  }).finally(() => {
    videoListLoading.value = false;
  });
}

// 获取放映室视频状态信息
const getVideoStateMessage = () => {
  getVideoState(roomId).then((response) => {
    roomViewerList.value = response.data.videoRoomUserList;

    //如果不是房主，更新视频进度
    if (videoRoom.value.creatorFlag === false && videoCanPlay.value === true) {
      videoRoom.value.state = response.data.state;
      videoRoom.value.roomName = response.data.roomName;
      videoRoom.value.videoTime = response.data.playTime;
      videoRoom.value.directoryPath = response.data.directoryPath;
      videoRoom.value.directoryOpenFlag = response.data.directoryOpenFlag;
      noticeList.value = [videoRoom.value.roomName];

      const nextVideoPath = normalizeResourceUrl(response.data.videoPath);
      if (videoRoom.value.videoPath !== nextVideoPath) {
        ElMessage({message: '房主切换了视频', type: 'info'})
        videoCanPlay.value = false;
        videoRoom.value.videoPath = nextVideoPath;
        nextTick(() => {
          videoRef.value?.load();
        });
      }

      //将进度条状态从'HH:mm:ss'转为播放组件的进度，如果进度条相差大于10秒，更新video组件的进度条
      const videoTime = videoRoom.value.videoTime.split(':');
      const videoTimeSecond = parseInt(videoTime[0]) * 3600 + parseInt(videoTime[1]) * 60 + parseInt(videoTime[2]);
      const timeOffset = Math.abs(videoTimeSecond - videoRef.value.currentTime);
      if (timeOffset > 5) {
        ElMessage({message: '同步时间' + response.data.playTime, type: 'info'})
        videoRef.value.currentTime = videoTimeSecond;
      }

      if (videoRoom.value.state === 'play') {
        if (videoRef.value.paused || timeOffset > 2) {
          videoRef.value.play();
        }
      } else if (videoRoom.value.state === 'pause') {
        if (!videoRef.value.paused) {
          videoRef.value.pause();
        }
      }
    }
  }).catch((error) => {
    if (!!error && !!error.code && error.code === 404) {
      videoRoomNotFoundHandle();
    }
  })
}

//放映室不存在时的处理
const videoRoomNotFoundHandle = () => {
  if (videoNotFoundNoticeFlag.value === false) {
    videoNotFoundNoticeFlag.value = true;

    //放映室不存在时清理资源
    beforeUnmountHandler();

    ElMessageBox.prompt(
        '当前放映室不存在或已关闭，可回到文件目录点击视频重新创建',
        '放映室不存在',
        {
          confirmButtonText: '回到文件目录',
          showCancelButton: false,
        }
    ).then(() => {
      router.push('/fileServer/publicDirectory');
    }).catch(() => {
      router.push('/fileServer/publicDirectory');
    })
  }
}

// 更新进度时上传视频进度状态
const updateVideoProgress = () => {
  //如果是房主操作才进行上传
  if (videoRoom.value.creatorFlag === true) {
    const videoTime = videoRef.value.currentTime;
    //格式化时间为HH:mm:ss，不足10时要补0
    const videoTimeHour = Math.floor(videoTime / 3600);
    const videoTimeMinute = Math.floor((videoTime % 3600) / 60);
    const videoTimeSecond = Math.floor(videoTime % 60);
    const videoTimeHourStr = videoTimeHour < 10 ? '0' + videoTimeHour : videoTimeHour;
    const videoTimeMinuteStr = videoTimeMinute < 10 ? '0' + videoTimeMinute : videoTimeMinute;
    const videoTimeSecondStr = videoTimeSecond < 10 ? '0' + videoTimeSecond : videoTimeSecond;

    const videoTimeStr = `${videoTimeHourStr}:${videoTimeMinuteStr}:${videoTimeSecondStr}`;
    videoRoom.value.videoTime = videoTimeStr;

    const updateVideoStateRequest = {
      roomId: roomId,
      state: videoRef.value.paused ? 'pause' : 'play',
      videoTime: videoTimeStr,
    }
    if (isSocketOpen()) {
      videoRoomSocket.send(JSON.stringify({
        type: 'PLAYBACK',
        state: updateVideoStateRequest.state,
        videoTimeSeconds: Math.floor(videoTime),
        clientSendTime: Date.now(),
      }));
    } else {
      updateVideoState(updateVideoStateRequest);
    }
  }
}

const closeVideoRoom = () => {
  ElMessageBox.confirm(
      '是否确定关闭当前放映室？',
      '是否确定',
      {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning',
        dangerouslyUseHTMLString: true,
      }
  ).then(() => {
    const classVideoRoomRequest = {
      roomId: videoRoom.value.roomId
    }
    //删除文件
    closeVideoRoomApi(classVideoRoomRequest).then((response) => {
      ElMessage.success('放映室已关闭');
      router.push('/fileServer/publicDirectory');
    })
  }).catch(() => {
    return false;
  })
}

const getRoomShareUrl = () => {
  getVideoRoomShareUrl(videoRoom.value.roomId).then((response) => {
    const baseUrl = import.meta.env.VITE_BASE_URL || `${window.location.origin}/`;
    let shareLink = baseUrl.replace(/\/$/, '/') + response.data.replace(/^\//, '');

    ElMessageBox.alert(
        '分享链接为：<a style="word-break: break-all;">' + shareLink + '</a>',
        '分享',
        {
          dangerouslyUseHTMLString: true,
        }
    )
  }).catch(() => {
    ElMessage.error('获取分享链接失败');
  })
}

onMounted(() => {
  // 获取视频信息
  getVideoRoomMessage();
  // 获取放映室视频状态信息
  getVideoStateMessage();
  refreshComments();
  connectVideoRoomSocket();

  // 添加事件监听器
  window.addEventListener('beforeunload', handleBeforeUnload);

  //定时器，每5秒执行一次，获取放映室视频进度和状态信息
  viewerTimer = setInterval(() => {
    if (!isSocketOpen() && videoRoom.value.creatorFlag === false && videoCanPlay.value === true) {
      getVideoStateMessage();
    }
  }, 5000);

  //房主定时器，每分钟执行一次，如果是房主且视频可播放，则更新放映室视频进度和状态信息
  creatorTimer = setInterval(() => {
    if (videoRoom.value.creatorFlag === true && videoCanPlay.value === true) {
      updateVideoProgress();
    }
  }, 60000);

  commentTimer = setInterval(() => {
    if (isSocketOpen()) {
      return;
    }
    refreshComments();
    if (viewerVisible.value) {
      refreshRoomMembers();
    }
  }, 30000);
})

onBeforeUnmount(() => {
  beforeUnmountHandler();
})

//关闭前清理资源
const beforeUnmountHandler = () => {
  //暂停并移除video组件的播放资源
  if (videoRef.value) {
    videoRef.value.pause();
    videoRef.value.removeAttribute('src')
    videoRef.value.load()
  }

  // 移除事件监听器
  window.removeEventListener('beforeunload', handleBeforeUnload)

  // 清理定时器
  clearInterval(viewerTimer);
  clearInterval(creatorTimer);
  clearInterval(commentTimer);
  closeVideoRoomSocket();
  viewerTimer = null;
  creatorTimer = null;
  commentTimer = null;
}

// 组件卸载时
const handleBeforeUnload = (event) => {
  // event.preventDefault();

  //调用退出放映室接口
  const quitVideoRoomRequest = {
    "roomId": roomId
  }
  quitVideoRoomApi(quitVideoRoomRequest);

  //如果是房主，先暂停播放，然后更新进度
  if (videoRoom.value.creatorFlag === true) {
    videoRef.value.pause();
    videoRoom.value.state = 'pause';
    updateVideoProgress();
  }
};

//退出房间
const quitVideoRoom = () => {
  //调用退出放映室接口
  const quitVideoRoomRequest = {
    "roomId": roomId
  }
  quitVideoRoomApi(quitVideoRoomRequest);
  //跳转到文件目录
  router.push('/fileServer/publicDirectory');
}
</script>

<style scoped>
/* ============================================================
   Video Room — warm-neutral, mobile-first, no dark scrim chrome.
   Layout:
     [notice]                <-- 28 high
     [video frame] 16:9       <-- max 65vh
     [actions row]            <-- horizontally scrollable on mobile
     [comment list]           <-- grows to fill remaining height
     [comment input]          <-- sticky bottom + safe-area
   ============================================================ */

.video-room {
  display: flex;
  flex-direction: column;
  width: 100%;
  flex: 1 1 auto;
  min-height: 0;
  max-width: 1080px;
  margin: 0 auto;
  padding: var(--space-4) var(--space-5) 0;
  gap: var(--space-3);
  background: var(--color-bg-base);
}

@media (max-width: 640px) {
  .video-room {
    padding: var(--space-3) var(--space-3) 0;
    gap: var(--space-2);
  }
}

/* ---------- Notice ---------- */
.video-notice {
  background: var(--color-bg-muted);
  border-radius: var(--radius-md);
  padding: 0 var(--space-3);
  flex-shrink: 0;
}

.video-notice-text {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  line-height: 28px;
  word-break: break-all;
}

/* Element Plus carousel internals */
:deep(.video-notice .el-carousel) {
  width: 100%;
}

:deep(.video-notice .el-carousel__container) {
  height: 28px !important;
}

/* ---------- Video frame ---------- */
.video-frame {
  width: 100%;
  aspect-ratio: 16 / 9;
  max-height: 65vh;
  background: #000;
  border-radius: var(--radius-md);
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.video-player {
  width: 100%;
  height: 100%;
  display: block;
  object-fit: contain;
  background: #000;
}

/* ---------- Actions row ---------- */
.video-actions {
  flex-shrink: 0;
  /* On mobile, allow horizontal scroll without showing the bar */
  overflow-x: auto;
  -ms-overflow-style: none;
  scrollbar-width: none;
}
.video-actions::-webkit-scrollbar { display: none; }

.video-actions-inner {
  display: flex;
  gap: var(--space-2);
  align-items: center;
  width: max-content;
  min-width: 100%;
  padding: 2px 0;
}

.video-chip {
  display: inline-flex;
  align-items: center;
  height: 28px;
  padding: 0 var(--space-3);
  border-radius: var(--radius-pill);
  background: var(--color-bg-muted);
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  white-space: nowrap;
  flex-shrink: 0;
}

.video-chip[data-tone="success"] {
  background: var(--color-success-soft);
  color: var(--color-success);
}

.video-chip[data-tone="warning"] {
  background: var(--color-warning-soft);
  color: var(--color-warning);
}

.video-chip[data-tone="info"],
.video-chip[data-tone=""],
.video-chip[data-tone] {
  /* default already handled above; left empty for explicitness */
}

.video-action {
  display: inline-flex;
  align-items: center;
  gap: var(--space-1);
  height: 36px;
  padding: 0 var(--space-3);
  border-radius: var(--radius-md);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: var(--font-size-sm);
  white-space: nowrap;
  flex-shrink: 0;
  transition:
      background var(--duration-fast) var(--ease-standard),
      color var(--duration-fast) var(--ease-standard);
}

.video-action:hover,
.video-action:active {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}

.video-action.danger {
  color: var(--color-danger);
}

.video-action.danger:hover,
.video-action.danger:active {
  background: var(--color-danger-soft);
  color: var(--color-danger);
}

.video-action :deep(svg) {
  width: 16px;
  height: 16px;
}

/* ---------- Comments ---------- */
.video-comments {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
  background: var(--color-bg-surface);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  overflow: hidden;
  margin-bottom: var(--space-4);
}

/* 关键：给评论列表显式封顶，避免在某些 flex 嵌套下不收敛（视频帧
   16:9 + 多评论时整页会撑高，外层 .app-content 反而代为滚动）。
   用 vh 而不是 % 是因为父链一旦没 clamp 住，% 就失效。 */
.comment-list {
  flex: 1 1 auto;
  min-height: 80px;
  max-height: 35vh;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  padding: var(--space-3) var(--space-4);
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

@media (min-width: 641px) {
  .comment-list {
    max-height: 45vh;
  }
}

.comment-item {
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
  padding: var(--space-2) var(--space-3);
  background: var(--color-bg-muted);
  border-radius: var(--radius-md);
}

.comment-meta {
  display: flex;
  align-items: baseline;
  gap: var(--space-2);
}

.comment-user {
  font-size: var(--font-size-sm);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
}

.comment-time {
  font-size: var(--font-size-xs);
  color: var(--color-text-tertiary);
}

.comment-content {
  margin: 0;
  font-size: var(--font-size-sm);
  color: var(--color-text-primary);
  line-height: var(--line-height-normal);
  word-break: break-word;
}

.comment-empty {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  padding: var(--space-6);
  color: var(--color-text-tertiary);
  font-size: var(--font-size-sm);
}

.comment-empty p {
  margin: 0;
}

.comment-empty-icon {
  font-size: 28px;
  color: var(--color-border-strong);
}

.comment-empty-icon :deep(svg) {
  width: 28px;
  height: 28px;
}

.comment-input-row {
  display: flex;
  gap: var(--space-2);
  align-items: flex-start;
  padding: var(--space-3) var(--space-3) max(var(--space-3), env(safe-area-inset-bottom));
  background: var(--color-bg-surface);
  border-top: 1px solid var(--color-border-subtle);
  position: sticky;
  bottom: 0;
}

.comment-input-row :deep(.el-input) {
  flex: 1 1 auto;
}

.comment-input-row :deep(.el-input__inner) {
  font-size: var(--font-size-md);
}
</style>
