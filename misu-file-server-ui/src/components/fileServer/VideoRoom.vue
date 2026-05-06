<template>
  <div class="video-room">
    <div class="video-top" style="display: inline;">
      <el-carousel height="30px" direction="vertical" :autoplay="false">
        <el-carousel-item v-for="item in noticeList" :key="item">
          <span class="notice-span">{{ item }}</span>
        </el-carousel-item>
      </el-carousel>
    </div>
    <div class="video-player-container">
      <video :src="videoRoom.videoPath"
             class="video-player"
             ref="videoRef"
             controls
             @play="updateVideoProgress"
             @pause="updateVideoProgress"
             @seeked="updateVideoProgress"
             preload="metadata"
             webkit-playsinline='true'
             playsinline='true'/>
    </div>
    <div class="video-room-action">
      <div class="video-room-left">
        <el-tag class="sync-mode-tag" :type="syncModeTagType" size="small">
          同步：{{ syncModeText }}
        </el-tag>
        <el-button @click="openViewerVisible" style="width: 80px"
            type="info"
            text>
          浏览人数：{{ roomViewerList.length }}
        </el-button>
        <el-button @click="openVideoListVisible()" style="width: 65px" type="info" text>
          播放列表
        </el-button>
      </div>
      <div class="video-room-right">
        <el-button
            v-if="videoRoom.creatorFlag === false"
            @click="quitVideoRoom"
            style="width: 65px"
            type="info"
            text>
          退出放映室
        </el-button>
        <el-button
            v-if="videoRoom.creatorFlag === true"
            @click="closeVideoRoom"
            style="width: 65px"
            type="info"
            text>
          结束放映
        </el-button>

        <el-button @click="getRoomShareUrl"
           type="info"
           style="width: 65px"
           :icon="Share"
           text>
          分享
        </el-button>
      </div>
    </div>
    <div class="video-comments">
      <div class="comment-list" v-if="commentList.length > 0" ref="commentListRef">
        <div class="comment-item" v-for="comment in commentList" :key="comment.createTime + comment.userName + comment.content">
          <span class="comment-user">{{ comment.userName }}</span>
          <span class="comment-content">{{ comment.content }}</span>
          <span class="comment-time">{{ comment.createTime }}</span>
        </div>
      </div>
      <div class="comment-empty" v-else>暂无评论</div>
      <div class="comment-input-row">
        <el-input
            placeholder="输入评论吧！"
            v-model="commentsInput"
            maxlength="500"
            show-word-limit
            @keyup.enter="sendRoomComment" />
        <el-button type="primary" :loading="commentSending" @click="sendRoomComment">发送</el-button>
      </div>
    </div>

    <el-dialog
        v-model="viewerVisible"
        width="min(520px, 92vw)"
        title="浏览者列表">
      <el-table :data="roomViewerList">
        <el-table-column type="index" />
        <el-table-column prop="userName" label="用户名" />
        <el-table-column prop="syncTime" label="最后在线" min-width="160" />
      </el-table>
    </el-dialog>

    <el-dialog
        v-model="videoListVisible"
        width="min(760px, 92vw)"
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
              <div v-if="scope.row.fileName !== videoRoom.roomName"><el-button link type="primary" @click="playVideo(scope.row)">播放</el-button></div>
              <div v-if="scope.row.fileName === videoRoom.roomName"><el-text type="info">当前播放</el-text></div>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute } from 'vue-router';
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
  setVideoRoomToCookie, createVideoRoom, getHistoryVideoRoomFromCookie
} from '@/api/fileServer/videoRoom';
import { getFileList as getFileListApi} from '@/api/fileServer/fileServer';
import { Share } from "@element-plus/icons-vue";
import { ElMessage, ElMessageBox} from "element-plus";
import router from "@/router";
import { getToken } from "@/api/auth/token";

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
const roomId = !route.params.roomId ? getHistoryVideoRoomFromCookie() : route.params.roomId;

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
    videoRoomNotFoundHandle();
  }

  getVideoRoomFromId(roomId).then((response) => {
    // 监听canplay事件
    videoRef.value.addEventListener('canplay', handleCanPlay);

    // 监听error事件
    videoRef.value.addEventListener('error', handleError);

    videoRoom.value = response.data;
    setVideoRoomToCookie(videoRoom.value.roomId)
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
      '当前放映室视频无法播放，可尝试刷新页面或者重新创建放映室',
      '放映室视频无法播放',
      {
        confirmButtonText: '从视频链接创建',
        cancelButtonText: '从文件创建',
        inputPlaceholder: '视频链接',
        inputPattern: /^(https?:\/\/)([a-zA-Z0-9-]+\.)+[a-zA-Z]{2,6}(:\d+)?(\/[^\s]*)?$/,
        inputErrorMessage: '视频链接不合法',
      }
  ).then(({ value }) => {
    createNewVideoRoom(value);
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
  nextTick(() => {
    const listEl = commentListRef.value;
    if (listEl) {
      listEl.scrollTop = listEl.scrollHeight;
    }
  });
};

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
  if (!event || videoRoom.value.creatorFlag === true || videoCanPlay.value !== true) {
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
  createNewVideoRoom(import.meta.env.VITE_RESOURCE_API + streamLink, file.fileName, videoRoom.value.directoryOpenFlag, videoRoom.value.directoryPath)
}

// 创建新的视频放映室
const createNewVideoRoom = (videoPath, videoName = '', directoryOpenFlag = null, directoryPath = null ) => {
  const createVideoRoomRequest = {
    roomName: videoName,
    directoryPath: directoryPath,
    directoryOpenFlag: directoryOpenFlag,
    videoPath: videoPath,
  }
  createVideoRoom(createVideoRoomRequest).then((response) => {
    ElMessage.success('创建放映室成功');
    //跳转到/fileServer/videoRoom/:roomId
    window.location.href = `/fileServer/videoRoom/${response.data.roomId}`;
  }).catch((error) => {
    console.error(error);
    ElMessage.error('放映室创建失败');
  });
}

//获取视频播放列表
const openVideoListVisible = () => {
  //显示列表弹窗
  videoList.value = [];
  videoListVisible.value = true;
  videoListLoading.value = true;
  //如果目录不为空，查询用户目录
  if (!!videoRoom.value.directoryPath) {
    getFileListApi(videoRoom.value.directoryPath, videoRoom.value.directoryOpenFlag).then((response) => {
      videoListLoading.value = false;
      videoList.value = response.data
          .sort((a, b) => a.fileName.localeCompare(b.fileName))
          .filter((file) => file.fileType === "video");
    }).catch(() => {
      videoListLoading.value = false;
    });
  }
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

      if (videoRoom.value.videoPath !== response.data.videoPath) {
        ElMessage({message: '房主切换了视频', type: 'info'})
        videoRoom.value.videoPath = response.data.videoPath;
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
        '当前放映室不存在或已关闭，可在通过视频链接或文件目录的视频重新创建放映室',
        '放映室不存在',
        {
          confirmButtonText: '从视频链接创建',
          cancelButtonText: '从文件创建',
          inputPlaceholder: '视频链接',
          inputPattern: /^(https?:\/\/)([a-zA-Z0-9-]+\.)+[a-zA-Z]{2,6}(:\d+)?(\/[^\s]*)?$/,
          inputErrorMessage: '视频链接不合法',
        }
    ).then(({value}) => {
      createNewVideoRoom(value);
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
    let shareLink = import.meta.env.VITE_BASE_URL + response.data;

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

.video-room{
  min-width: 100%;
  min-height: 100%;
  background-image: url("/videoRoomBackground.jpg");
  background-size: auto;  /* 图片保持原始大小 */
  background-repeat: repeat;  /* 图片会在容器中重复 */
  display: flex;
  flex-wrap: wrap; /* 自动换行 */
  align-content: flex-start;
  justify-content: center;  /* 水平居中 */
  gap: 10px;
}

.video-top {
  width: 90%;
  padding-top: 10px;
}

.video-player-container {
  display: flex;
  justify-content: center; /* 水平居中 */
  align-items: center; /* 垂直居中 */
  width: 90%;
  max-height: 65svh;
  background-color: #000000;
}

.video-player {
  max-width: 100%;
  max-height: 100%
}

.video-room-action {
  width: 90%;
  display: flex;
  justify-content: space-between; /* 左侧是路径，右侧是按钮区域 */
}

.video-room-left {
  display: flex;
  align-items: center;
  color: #b3b3b3;
}

.video-room-right {
  display: flex;
}

.sync-mode-tag {
  flex-shrink: 0;
  margin-right: 8px;
}

.video-comments {
  width: 90%;
  color: white;
  padding-bottom: 10px;
}

.comment-list {
  display: grid;
  gap: 8px;
  max-height: 18svh;
  overflow-y: auto;
  padding: 8px 0;
}

.comment-item {
  align-items: flex-start;
  background-color: rgba(0, 0, 0, 0.35);
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 8px;
  display: grid;
  gap: 4px;
  grid-template-columns: auto 1fr auto;
  padding: 8px 10px;
}

.comment-user {
  color: #d8e6ff;
  font-weight: 600;
  white-space: nowrap;
}

.comment-content {
  color: #ffffff;
  line-height: 1.4;
  word-break: break-all;
}

.comment-time {
  color: rgba(255, 255, 255, 0.55);
  font-size: 12px;
  white-space: nowrap;
}

.comment-empty {
  color: rgba(255, 255, 255, 0.65);
  font-size: 13px;
  padding: 8px 0;
}

.comment-input-row {
  display: grid;
  gap: 8px;
  grid-template-columns: 1fr auto;
}

.notice-span {
  color: gray;
  padding-left: 10px;
  word-break: break-all;
}

/** el-input disabled时的背景和边框*/
:deep(.el-input__wrapper){
  background-color:rgba(0,0,0,0.3);
}

:deep(.el-input__inner) {
  background-color: rgba(0, 0, 0, 0);
}

@media (max-width: 760px) {
  .video-room {
    gap: 8px;
  }

  .video-top,
  .video-player-container,
  .video-room-action,
  .video-comments {
    width: 94%;
  }

  .video-player-container {
    max-height: 52svh;
  }

  .video-room-action {
    align-items: stretch;
    display: grid;
    gap: 8px;
  }

  .video-room-left,
  .video-room-right {
    align-items: center;
    display: grid;
    gap: 6px;
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .video-room-left .el-button,
  .video-room-right .el-button {
    margin-left: 0;
    width: auto !important;
  }

  .sync-mode-tag {
    justify-content: center;
    margin-right: 0;
    width: 100%;
  }

  .comment-list {
    max-height: 24svh;
  }

  .comment-item {
    grid-template-columns: 1fr;
  }

  .comment-time {
    white-space: normal;
  }

  .comment-input-row {
    grid-template-columns: 1fr;
  }
}

</style>
