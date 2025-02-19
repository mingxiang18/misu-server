import request from '@/api/request'

// 获取目录的文件列表
export function getFileList(filePath, openType) {
    const params = {
        filePath,
        openType
    }
    return request({
        url: '/fileServer/file/getFileList',
        method: 'get',
        params: params
    })
}

// 获取目录下的文件下载链接
export function getFileDownloadLink(filePath, openType) {
    const params = {
        filePath,
        openType
    }
    return request({
        url: '/fileServer/file/getFileDownloadLink',
        method: 'get',
        params: params
    })
}

// 上传文件
export function uploadFile(file) {
    return request({
        url: '/fileServer/file/uploadFile',
        method: 'post',
        data: file
    })
}

// 移动文件
export function moveFile(originFilePath, newFilePath, openType) {
    const data = {
        originFilePath,
        newFilePath,
        openType
    }
    return request({
        url: '/fileServer/file/moveFile',
        method: 'post',
        data: data
    })
}

// 删除文件
export function deleteFile(filePath, openType) {
    const data = {
        filePath,
        openType
    }
    return request({
        url: '/fileServer/file/deleteFile',
        method: 'post',
        data: data
    })
}

// 创建文件目录
export function createDirectory(filePath, openType) {
    const data = {
        filePath,
        openType
    }
    return request({
        url: '/fileServer/file/createDirectory',
        method: 'post',
        data: data
    })
}