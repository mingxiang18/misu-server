import request from '@/api/request'

// 获取bot连接用的webSocket的url
export function getServerWebSocketUrl() {
    return request({
        url: '/fileServer/bot/getServerWebSocketUrl',
        method: 'get'
    });
}

// 获取bot访问token
export function getBotAccessToken() {
    return request({
        url: '/fileServer/bot/getBotAccessToken',
        method: 'get'
    });
}