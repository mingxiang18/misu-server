import request from '@/api/request'
import {getRefreshToken, removeLoginTokens} from '@/api/auth/token'
import {removeUserInfo} from '@/api/user/user'
import {removeVideoRoomFromCookie} from '@/api/fileServer/videoRoom'

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
    removeLoginTokens();
    removeUserInfo();
    removeVideoRoomFromCookie();
}
