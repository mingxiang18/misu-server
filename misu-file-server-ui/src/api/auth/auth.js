import request from '@/api/request'
import {removeToken} from '@/api/auth/token'

// 登录方法
export function login(userName, password, captchaCode) {
    const data = {
        userName,
        password,
        captchaCode
    }
    return request({
        url: '/auth/login',
        headers: {
            isToken: false
        },
        method: 'post',
        data: data
    })
}

// 退出登录
export function logOut() {
    removeToken();
}