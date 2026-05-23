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