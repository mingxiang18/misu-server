import request from '@/api/request'

/**
 * 查询审计日志（仅管理员）
 *
 * @param {object} q { userId, actionType, since, until, pageNumber, pageSize }
 *   since/until 接受 ISO 字符串（new Date().toISOString()）
 */
export function listAuditLogs(q = {}) {
    return request({
        url: '/fileServer/audit/list',
        method: 'get',
        params: q
    })
}
