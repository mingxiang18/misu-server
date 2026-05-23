import request from '@/api/request'

// 获取bot连接用的webSocket的url
export function getServerWebSocketUrl() {
    return request({
        url: '/fileServer/chat/getServerWebSocketUrl',
        method: 'get'
    });
}

// 获取聊天访问token
export function getAccessToken() {
    return request({
        url: '/fileServer/chat/getAccessToken',
        method: 'get'
    });
}

// 我的会话列表
export function listConversations() {
    return request({
        url: '/fileServer/chat/conversation/list',
        method: 'get'
    });
}

// 取/创建与 bb 的私聊会话
export function getPrivateConversation() {
    return request({
        url: '/fileServer/chat/conversation/private',
        method: 'get'
    });
}

// 会话历史消息分页（beforeId 为空取最新一页）
export function pageMessages(conversationId, params) {
    return request({
        url: `/fileServer/chat/conversation/${conversationId}/message/page`,
        method: 'get',
        params
    });
}

// 标记会话已读（清未读）
export function markRead(conversationId) {
    return request({ url: `/fileServer/chat/conversation/${conversationId}/read`, method: 'post' });
}

// 创建群聊
export function createGroup(data) {
    return request({ url: '/fileServer/chat/conversation/group', method: 'post', data });
}

// 添加群成员
export function addGroupMembers(conversationId, userIds) {
    return request({ url: `/fileServer/chat/conversation/${conversationId}/member`, method: 'post', data: { userIds } });
}

// 移除群成员 / 退群
export function removeGroupMember(conversationId, userId) {
    return request({ url: `/fileServer/chat/conversation/${conversationId}/member/${userId}`, method: 'delete' });
}

// 群成员列表（含 bb）
export function listGroupMembers(conversationId) {
    return request({ url: `/fileServer/chat/conversation/${conversationId}/member/list`, method: 'get' });
}

// 搜索用户（建群选人）
export function searchUsers(keyword) {
    return request({ url: '/account/user/search', method: 'get', params: { keyword } });
}

// bb 全局资料（名称/头像）
export function getBotProfile() {
    return request({ url: '/fileServer/chat/bot/profile', method: 'get' });
}

// 设置 bb 全局头像（仅 ADMIN）
export function setBotAvatarApi(avatar) {
    return request({ url: '/fileServer/chat/bot/profile/avatar', method: 'post', data: { avatar } });
}

// 上传文件到磁盘，返回 {id, fileName, mimeType, size, category}（消息里放引用）
export function uploadChatFile(conversationId, file, category) {
    const fd = new FormData();
    fd.append('file', file);
    if (category) fd.append('category', category);
    return request({
        url: `/fileServer/chat/conversation/${conversationId}/file/upload`,
        method: 'post',
        data: fd,
        headers: { 'Content-Type': 'multipart/form-data' }
    });
}

// 群文件下载地址（cookie 鉴权，可直接用于 <img src> / <a href>）
export function chatFileUrl(fileId) {
    const base = import.meta.env.VITE_BASE_API || '/';
    return `${base}fileServer/chat/file/${fileId}/download`;
}

// 群文件列表
export function listGroupFiles(conversationId) {
    return request({ url: `/fileServer/chat/conversation/${conversationId}/file/list`, method: 'get' });
}

// 下载群文件（二进制，走 axios 带鉴权）
export function downloadGroupFile(fileId) {
    return request({ url: `/fileServer/chat/file/${fileId}/download`, method: 'get', responseType: 'arraybuffer' });
}

// 删除群文件
export function deleteGroupFile(fileId) {
    return request({ url: `/fileServer/chat/file/${fileId}`, method: 'delete' });
}