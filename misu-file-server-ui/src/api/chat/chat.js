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