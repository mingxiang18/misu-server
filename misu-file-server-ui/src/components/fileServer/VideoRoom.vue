<template>
  <div class="video-room">
    <div class="video-top" style="display: inline;">
      <el-carousel height="30px" direction="vertical" :autoplay="false">
        <el-carousel-item v-for="item in noticeList" :key="item">
          <span class="notice-span">{{ item }}</span>
        </el-carousel-item>
      </el-carousel>
    </div>
    <div class="video-player">
      <video :src="videoRoom.videoPath"
             ref="videoRef"
             controls
             @play="updateVideoProgress"
             @pause="updateVideoProgress"
             @seeked="updateVideoProgress"
             preload="auto"
             width="100%"
             webkit-playsinline='true'
             playsinline='true'
             height="auto"/>
    </div>
    <div class="video-room-action">
      <div class="video-room-left">
        <el-button @click="viewerVisible = !viewerVisible"
            type="info"
            text>
          浏览人数：{{ roomViewerList.length }}
        </el-button>
      </div>
      <div class="video-room-right">
        <el-button
            v-if="videoRoom.creatorFlag === false"
            @click="quitVideoRoom"
            type="info"
            text>
          退出放映室
        </el-button>
        <el-button
            v-if="videoRoom.creatorFlag === true"
            @click="closeVideoRoom"
            type="info"
            text>
          结束放映
        </el-button>

        <el-button @click="getRoomShareUrl"
           type="info"
           :icon="Share"
           text>
          分享
        </el-button>
      </div>
    </div>
    <div class="video-comments">
      <el-input placeholder="输入评论吧！" v-model="commentsInput" ></el-input>
    </div>

    <el-dialog
        v-model="viewerVisible"
        style="width: 80%;"
        title="浏览者列表">
      <el-table :data="roomViewerList">
        <el-table-column type="index" />
        <el-table-column prop="userName" label="用户名" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref } from "vue";
import { useRouter, useRoute } from 'vue-router';
import {
  getVideoRoomFromId,
  getVideoState,
  getVideoRoomShareUrl,
  updateVideoState,
  quitVideoRoom as quitVideoRoomApi,
  closeVideoRoom as closeVideoRoomApi,
  setVideoRoomToCookie, createVideoRoom, getHistoryVideoRoomFromCookie
} from '@/api/fileServer/videoRoom';
import { getUserInfo } from '@/api/user/user';
import { Share } from "@element-plus/icons-vue";
import {ElMessage, ElMessageBox} from "element-plus";
import router from "@/router";

// 基本url
const baseUrl = import.meta.env.VITE_BASE_API

// 引用 video 元素
const videoRef = ref(null);
const videoRoom = ref({
  roomId: '',
  roomName: '',
  videoPath: '',
  playTime: '',
  creatorId: '',
  creatorFlag: false,
});

// 在url中获取id
const route = useRoute();
const roomId = !route.params.roomId ? getHistoryVideoRoomFromCookie() : route.params.roomId;
const userInfo = getUserInfo();

const videoCanPlay = ref(false);

const noticeList = ref(['通知']);
const commentsInput = ref('');

const roomViewerList = ref([]);

const viewerVisible = ref(false);

let viewerTimer = null;
let creatorTimer = null;

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

// 创建新的视频放映室
const createNewVideoRoom = (videoPath) => {
  const createVideoRoomRequest = {
    roomName: '',
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

// 获取放映室视频状态信息
const getVideoStateMessage = () => {
  getVideoState(roomId).then((response) => {
    roomViewerList.value = response.data.videoRoomUserList;

    //如果不是房主，更新视频进度
    if (videoRoom.value.creatorFlag === false && videoCanPlay.value === true) {
      videoRoom.value.state = response.data.state;
      videoRoom.value.videoTime = response.data.playTime;

      //将进度条状态从'HH:mm:ss'转为播放组件的进度，如果进度条相差大于10秒，更新video组件的进度条
      const videoTime = videoRoom.value.videoTime.split(':');
      const videoTimeSecond = parseInt(videoTime[0]) * 3600 + parseInt(videoTime[1]) * 60 + parseInt(videoTime[2]);
      if (Math.abs(videoTimeSecond - videoRef.value.currentTime) > 10) {
        ElMessage({message: '同步时间' + response.data.playTime, type: 'info'})
        videoRef.value.currentTime = videoTimeSecond;
      }

      if (videoRoom.value.state === 'play') {
        videoRef.value.play();
      } else if (videoRoom.value.state === 'pause') {
        videoRef.value.pause();
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
    updateVideoState(updateVideoStateRequest);
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
    return done(true);
  }).catch(() => {
    return false;
  })
}

const getRoomShareUrl = () => {
  getVideoRoomShareUrl(videoRoom.value.roomId).then((response) => {
    let shareLink = baseUrl + response.data;

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

  // 添加事件监听器
  window.addEventListener('beforeunload', handleBeforeUnload);

  //定时器，每5秒执行一次，获取放映室视频进度和状态信息
  viewerTimer = setInterval(() => {
    getVideoStateMessage();
  }, 5000);

  //房主定时器，每60秒执行一次，如果是房主且视频可播放，则更新放映室视频进度和状态信息
  creatorTimer = setInterval(() => {
    if (videoRoom.value.creatorFlag === true && videoCanPlay.value === true) {
      updateVideoProgress();
    }
  }, 60000);
})

onBeforeUnmount(() => {
  beforeUnmountHandler();
})

//关闭前清理资源
const beforeUnmountHandler = () => {
  //暂停并移除video组件的播放资源
  videoRef.value.pause();
  videoRef.value.removeAttribute('src')
  videoRef.value.load()

  // 移除事件监听器
  window.removeEventListener('beforeunload', handleBeforeUnload)

  // 清理定时器
  clearInterval(viewerTimer);
  clearInterval(creatorTimer);
  viewerTimer = null;
  creatorTimer = null;
}

// 组件卸载时
const handleBeforeUnload = (event) => {
  event.preventDefault();

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

.video-player {
  width: 90%;
}

.video-room-action {
  width: 90%;
  display: flex;
  justify-content: space-between; /* 左侧是路径，右侧是按钮区域 */
}

.video-room-left {
  display: flex;
  color: #b3b3b3;
}

.video-room-right {
  display: flex;
}

.video-comments {
  width: 90%;
  color: white;
  padding-bottom: 10px;
}

.notice-span {
  color: gray;
  padding-left: 10px;
}

/** el-input disabled时的背景和边框*/
:deep(.el-input__wrapper){
  background-color:rgba(0,0,0,0.3);
}

:deep(.el-input__inner) {
  background-color: rgba(0, 0, 0, 0);
}

</style>