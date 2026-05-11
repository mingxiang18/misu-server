import request from '@/api/request'

/** 列出指定文件的版本（按 versionNo 倒序） */
export function listVersions({ openType, filePath }) {
    return request({
        url: '/fileServer/version/list',
        method: 'get',
        params: { openType, filePath }
    })
}

/** 还原指定版本 */
export function restoreVersion(id) {
    return request({
        url: '/fileServer/version/restore',
        method: 'post',
        data: { id }
    })
}

/** 删除单个版本 */
export function purgeVersion(id) {
    return request({
        url: '/fileServer/version/purge',
        method: 'post',
        data: { id }
    })
}
