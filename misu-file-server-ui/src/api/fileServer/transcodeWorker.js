import request from '@/api/request'

const BASE = '/fileServer/transcodeWorker'

export function getWorkerHealth() {
  return request({ url: `${BASE}/health`, method: 'get' })
}

export function getWorkerState() {
  return request({ url: `${BASE}/state`, method: 'get' })
}

export function listWorkerTasks(bucket = 'queue', limit = 50) {
  return request({
    url: `${BASE}/tasks`,
    method: 'get',
    params: { bucket, limit }
  })
}

export function workerRecoverRunning() {
  return request({ url: `${BASE}/recoverRunning`, method: 'post' })
}

export function workerRetry(taskId) {
  return request({ url: `${BASE}/retry/${encodeURIComponent(taskId)}`, method: 'post' })
}
