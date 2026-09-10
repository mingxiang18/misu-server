import request from '@/api/request'
import {getRefreshToken, getToken, removeLoginTokens} from '@/api/auth/token'
import {getUserInfo, removeUserInfo} from '@/api/user/user'

// 登录方法
export function login(userName, password, captchaCode) {
    const data = {
        userName,
        password,
        captchaCode
    }
    return request({
        url: '/account/auth/login',
        headers: {
            isToken: false
        },
        method: 'post',
        data: data
    })
}

// 刷新短期token
export function refreshToken() {
    return request({
        url: '/account/auth/refresh-token',
        headers: {
            isToken: false,
            skipAuthRefresh: true
        },
        method: 'post',
        data: {
            refreshToken: getRefreshToken()
        }
    })
}

// 退出登录
export function logOut() {
    // 清除登录信息前撤销运维会话；运维服务不可用时也允许正常退出。
    const token = getToken()
    if (token && (getUserInfo().authorities || []).includes('ADMIN')) {
        request({
            url: '/ops/api/sessions/revoke',
            method: 'post',
            timeout: 2000,
            silent: true,
            headers: {
                Authorization: `Bearer ${token}`,
                isToken: false,
                skipAuthRefresh: true
            }
        }).catch(() => {})
    }
    removeLoginTokens();
    removeUserInfo();
}
