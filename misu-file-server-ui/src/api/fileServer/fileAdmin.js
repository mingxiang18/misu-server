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
