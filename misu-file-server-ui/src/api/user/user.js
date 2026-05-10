import request from '@/api/request'
import Cookies from 'js-cookie'
import logger from '@/utils/logger'

const UserKey = 'User-Info';

// 获取用户信息
export function getUserInfoFromToken() {
    return request({
        url: '/account/user/getUserFromToken',
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
            logger.error(e);
            return {}
        }
    }
    return {};
}

export function setUserInfo(userInfo) {
    //将用户信息设置到本地
    return getUserInfoFromToken().then(response => {
        userInfo = response.data;
        Cookies.set(UserKey, JSON.stringify(userInfo));
        return userInfo;
    })
}

export function removeUserInfo() {
    Cookies.remove(UserKey);
}

// 分页查询用户
export function listUsers(params) {
    return request({
        url: '/account/user/manage/page',
        method: 'get',
        params
    })
}

// 查询用户详情
export function getUserDetail(userId) {
    return request({
        url: `/account/user/manage/${userId}`,
        method: 'get'
    })
}

// 新增用户
export function addUser(data) {
    return request({
        url: '/account/user/manage',
        method: 'post',
        data
    })
}

// 修改用户
export function updateUser(data) {
    return request({
        url: '/account/user/manage',
        method: 'put',
        data
    })
}

// 删除用户
export function deleteUser(userId) {
    return request({
        url: `/account/user/manage/${userId}`,
        method: 'delete'
    })
}

// 重置密码
export function resetUserPassword(data) {
    return request({
        url: '/account/user/manage/resetPassword',
        method: 'put',
        data
    })
}
