import request from '@/api/request'

/** 创建外链分享。expireMinutes 默认 1440(24h)；password / maxDownloads 可选。 */
export function createShare({ openType, filePath, expireMinutes = 1440, password, maxDownloads }) {
    return request({
        url: '/fileServer/share/create',
        method: 'post',
        data: { openType, filePath, expireMinutes, password, maxDownloads }
    })
}

/** 我的分享列表 */
export function listShares({ pageNumber = 1, pageSize = 20 } = {}) {
    return request({
        url: '/fileServer/share/list',
        method: 'get',
        params: { pageNumber, pageSize }
    })
}

/** 撤销分享 */
export function revokeShare(id) {
    return request({
        url: '/fileServer/share/revoke',
        method: 'post',
        data: { id }
    })
}

/** 公开 — 查询分享元信息（不需要登录） */
export function getSharedInfo(token) {
    return request({
        url: '/fileServer/share/info',
        method: 'get',
        params: { token },
        headers: { isToken: false }
    })
}

/** 公开 — 拼接出下载 URL，前端用 window.location 触发浏览器下载即可 */
export function buildSharedDownloadUrl(token, password, baseApi) {
    const base = (baseApi || import.meta.env.VITE_BASE_API || '').replace(/\/$/, '')
    const params = new URLSearchParams({ token })
    if (password) params.append('password', password)
    return `${base}/fileServer/share/download?${params.toString()}`
}
