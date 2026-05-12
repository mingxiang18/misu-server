import request from '@/api/request'

export function startFileMappingBackfill() {
    return request({
        url: '/fileServer/fileAdmin/startFileMappingBackfill',
        method: 'post'
    })
}

export function getFileMappingBackfillStatus() {
    return request({
        url: '/fileServer/fileAdmin/getFileMappingBackfillStatus',
        method: 'get'
    })
}

// 管理员视角：浏览指定用户在指定 openType 下的文件列表
export function listUserFilesAsAdmin({ userId, openType, parentPath } = {}) {
    return request({
        url: '/fileServer/fileAdmin/listUserFiles',
        method: 'get',
        params: { userId, openType, parentPath }
    })
}

// 管理员视角：指定用户的存储用量
export function getUserStorageUsageAsAdmin({ userId, openType } = {}) {
    return request({
        url: '/fileServer/fileAdmin/getUserStorageUsage',
        method: 'get',
        params: { userId, openType }
    })
}

// staging 物理目录根
export function getStagingRoot() {
    return request({
        url: '/fileServer/fileAdmin/getStagingRoot',
        method: 'get'
    })
}

// staging 目录列出
export function listStaging(subPath) {
    return request({
        url: '/fileServer/fileAdmin/listStaging',
        method: 'get',
        params: { subPath }
    })
}

// staging → 公共
export function shareStagingToPublic({ sourceStagingPath, targetVirtualPath }) {
    return request({
        url: '/fileServer/fileAdmin/shareStagingToPublic',
        method: 'post',
        data: { sourceStagingPath, targetVirtualPath }
    })
}

// staging → 用户私人
export function shareStagingToUser({ sourceStagingPath, targetUserId, targetVirtualPath }) {
    return request({
        url: '/fileServer/fileAdmin/shareStagingToUser',
        method: 'post',
        data: { sourceStagingPath, targetUserId, targetVirtualPath }
    })
}
