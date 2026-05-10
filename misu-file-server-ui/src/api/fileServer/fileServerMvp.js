import request from '@/api/request'

/**
 * 文件搜索（按文件名模糊匹配，分页）
 */
export function searchFiles({ keyword, openType, fileType, pageNumber = 1, pageSize = 50 }) {
    return request({
        url: '/fileServer/file/search',
        method: 'get',
        params: { keyword, openType, fileType, pageNumber, pageSize }
    })
}

/**
 * 回收站列表（按删除时间倒序）
 */
export function listTrash({ openType, pageNumber = 1, pageSize = 50 }) {
    return request({
        url: '/fileServer/file/listTrash',
        method: 'get',
        params: { openType, pageNumber, pageSize }
    })
}

/**
 * 从回收站还原指定项
 */
export function restoreTrash(id) {
    return request({
        url: '/fileServer/file/restoreTrash',
        method: 'post',
        data: { id }
    })
}

/**
 * 永久删除回收站项
 */
export function purgeTrash(id) {
    return request({
        url: '/fileServer/file/purgeTrash',
        method: 'post',
        data: { id }
    })
}

/**
 * 批量软删（一次最多 500 项）
 */
export function batchDelete(openType, filePaths) {
    return request({
        url: '/fileServer/file/batchDelete',
        method: 'post',
        data: { openType, filePaths }
    })
}

/**
 * 批量移动到目标父目录（保留原文件名；targetParentPath 空字符串代表根目录）
 */
export function batchMove(openType, filePaths, targetParentPath) {
    return request({
        url: '/fileServer/file/batchMove',
        method: 'post',
        data: { openType, filePaths, targetParentPath }
    })
}

/**
 * 当前用户存储用量与配额
 */
export function getStorageUsage(openType) {
    return request({
        url: '/fileServer/file/getStorageUsage',
        method: 'get',
        params: { openType }
    })
}

/**
 * 哈希秒传校验。命中即秒传成功，不命中前端再走原 /uploadFile 分片上传。
 *
 * @param {object} payload {openType, fileName, filePath, fileMd5, fileSize, coverFlag}
 */
export function checkUploadByHash(payload) {
    return request({
        url: '/fileServer/file/checkUploadByHash',
        method: 'post',
        data: payload
    })
}

/**
 * 续传探测：传入目标 (openType, filePath, fileName, totalChunks)，返回已传分片索引。
 */
export function getUploadStatus({ openType, fileName, filePath, totalChunks }) {
    return request({
        url: '/fileServer/file/getUploadStatus',
        method: 'get',
        params: { openType, fileName, filePath, totalChunks }
    })
}

/**
 * 文件夹流式 ZIP 下载链接（直接拼好的 URL，前端用 a.href 触发即可）
 */
export function buildDirectoryDownloadUrl(filePath, openType, baseApi) {
    const base = (baseApi || import.meta.env.VITE_BASE_API || '').replace(/\/$/, '')
    const params = new URLSearchParams({ filePath, openType: String(openType) })
    return `${base}/fileServer/file/downloadDirectory?${params.toString()}`
}
