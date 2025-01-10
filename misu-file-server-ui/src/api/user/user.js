import request from '@/api/request'
import Cookies from 'js-cookie'

const UserKey = 'User-Info';

// 获取用户信息
export function getUserInfoFromToken() {
    return request({
        url: '/user/getUserFromToken',
        method: 'get'
    })
}

export function getUserInfo() {
    //从Cookie获取用户信息
    const userInfoStr = Cookies.get(UserKey);
    if (userInfoStr) {
        try {
            return JSON.parse(userInfoStr);
        }catch (e) {
            console.log(e);
            return {}
        }
    }
    return {};
}

export function setUserInfo(userInfo) {
    //将用户信息设置到本地
    getUserInfoFromToken().then(response => {
        userInfo = response.data;
        Cookies.set(UserKey, JSON.stringify(userInfo));
    })
}

export function removeUserInfo() {
    Cookies.remove(UserKey);
}