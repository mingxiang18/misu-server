import request from '@/api/request'

// 获取用户信息
export function getUserInfo() {
    return request({
        url: '/user/getUserFromToken',
        method: 'get'
    })
}