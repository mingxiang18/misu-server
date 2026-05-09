import request from '@/api/request'

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

// 获取房间成员列表
export function getRoomMembers(roomId) {
    const params = {
        roomId
    }
    return request({
        url: '/fileServer/videoRoom/getRoomMembers',
        method: 'get',
        params: params
    })
}

// 发送放映室评论
export function sendComment(sendCommentRequest) {
    return request({
        url: '/fileServer/videoRoom/sendComment',
        method: 'post',
        data: sendCommentRequest
    })
}

// 获取放映室评论
export function getComments(roomId) {
    const params = {
        roomId
    }
    return request({
        url: '/fileServer/videoRoom/getComments',
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

// 获取当前用户活动放映室
export function getMyActiveRoom() {
    return request({
        url: '/fileServer/videoRoom/myActiveRoom',
        method: 'get'
    })
}

// 播放当前用户选中的视频
export function playMyVideo(playMyVideoRequest) {
    return request({
        url: '/fileServer/videoRoom/playMyVideo',
        method: 'post',
        data: playMyVideoRequest
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
