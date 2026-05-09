import request from '@/api/request'

export function getTranscodeTaskSummary() {
    return request({
        url: '/fileServer/videoTranscodeAdmin/getTaskSummary',
        method: 'get'
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

export function recoverRunningTasks() {
    return request({
        url: '/fileServer/videoTranscodeAdmin/recoverRunningTasks',
        method: 'post'
    })
}
