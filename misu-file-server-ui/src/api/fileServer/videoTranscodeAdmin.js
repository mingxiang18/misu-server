import request from '@/api/request'

export function getTranscodeTaskSummary() {
    return request({
        url: '/fileServer/videoTranscodeAdmin/getTaskSummary',
        method: 'get'
    })
}

export function getTranscodeJobs({ state, queueState, keyword, page = 0, size = 20 } = {}) {
    return request({
        url: '/fileServer/videoTranscodeAdmin/jobs',
        method: 'get',
        params: { state, queueState, keyword, page, size }
    })
}

export function retryFailedTask(taskId) {
    return request({
        url: '/fileServer/videoTranscodeAdmin/retryFailedTask',
        method: 'post',
        data: { taskId }
    })
}

export function retryAllFailedTasks() {
    return request({
        url: '/fileServer/videoTranscodeAdmin/retryAllFailedTasks',
        method: 'post'
    })
}

export function retryTranscodeBatch(taskIds) {
    return request({
        url: '/fileServer/videoTranscodeAdmin/retryBatch',
        method: 'post',
        data: { taskIds }
    })
}

export function reTranscodeBatch(taskIds) {
    return request({
        url: '/fileServer/videoTranscodeAdmin/reTranscodeBatch',
        method: 'post',
        data: { taskIds }
    })
}

export function recoverRunningTasks() {
    return request({
        url: '/fileServer/videoTranscodeAdmin/recoverRunningTasks',
        method: 'post'
    })
}
