import request from '@/api/request'
import Cookies from 'js-cookie'

const VideoRoomKey = 'video-room-url';

// 根据id获取对应的放映室数据
export function getVideoRoomFromId(roomId) {
    const params = {
        roomId
    }
    return request({
        url: '/fileServer/videoRoom/getVideoRoomFromId',
        method: 'get',
        params: params
    })
}

// 根据id获取对应的放映进度
export function getVideoState(roomId) {
    const params = {
        roomId
    }
    return request({
        url: '/fileServer/videoRoom/getVideoState',
        method: 'get',
        params: params
    })
}

// 根据id获取对应的分享链接
export function getVideoRoomShareUrl(roomId) {
    const params = {
        roomId
    }
    return request({
        url: '/fileServer/videoRoom/getVideoRoomShareUrl',
        method: 'get',
        params: params
    })
}

// 创建放映室
export function createVideoRoom(createVideoRoomRequest) {
    return request({
        url: '/fileServer/videoRoom/createVideoRoom',
        method: 'post',
        data: createVideoRoomRequest
    })
}

// 更新放映室视频状态
export function updateVideoState(updateVideoStateRequest) {
    return request({
        url: '/fileServer/videoRoom/updateVideoState',
        method: 'post',
        data: updateVideoStateRequest
    })
}

// 退出放映室
export function quitVideoRoom(quitVideoRoomRequest) {
    return request({
        url: '/fileServer/videoRoom/quitVideoRoom',
        method: 'post',
        data: quitVideoRoomRequest
    })
}

// 关闭放映室
export function closeVideoRoom(closeVideoRoomRequest) {
    return request({
        url: '/fileServer/videoRoom/closeVideoRoom',
        method: 'post',
        data: closeVideoRoomRequest
    })
}

// 把当前进入的放映室添加到Cookie
export function setVideoRoomToCookie(videoRoomId) {
    return Cookies.set(VideoRoomKey, videoRoomId);
}

// 从Cookie中获取最后一次进入的放映室
export function getHistoryVideoRoomFromCookie() {
    return Cookies.get(VideoRoomKey);
}

// 清理Cookie中记录的放映室
export function removeVideoRoomFromCookie() {
    return Cookies.remove(VideoRoomKey);
}