import request from '@/api/request'

// 获取rss订阅列表
export function getRssList(queryParams) {
    return request({
        url: '/fileServer/torrent/getRssList',
        method: 'get',
        params: queryParams
    });
}

// 获取rss订阅详情
export function getRssDetail(queryParams) {
    return request({
        url: '/fileServer/torrent/getRssDetail',
        method: 'get',
        params: queryParams
    });
}

// 获取rss条目历史
export function getRssItems(queryParams) {
    return request({
        url: '/fileServer/torrent/getRssItems',
        method: 'get',
        params: queryParams
    });
}

// 手动刷新rss订阅
export function refreshRss(refreshRssRequestDto) {
    return request({
        url: '/fileServer/torrent/refreshRss',
        method: 'post',
        data: refreshRssRequestDto
    });
}

// 获取rss规则列表
export function getRssRuleList(queryParams) {
    return request({
        url: '/fileServer/torrent/getRssRuleList',
        method: 'get',
        params: queryParams
    });
}

// 添加rss规则
export function addRssRule(addRssRuleRequestDto) {
    return request({
        url: '/fileServer/torrent/addRssRule',
        method: 'post',
        data: addRssRuleRequestDto
    });
}

// 更新rss规则
export function updateRssRule(updateRssRuleRequestDto) {
    return request({
        url: '/fileServer/torrent/updateRssRule',
        method: 'post',
        data: updateRssRuleRequestDto
    });
}

// 移除rss规则
export function removeRssRule(deleteRssRuleRequestDto) {
    return request({
        url: '/fileServer/torrent/removeRssRule',
        method: 'post',
        data: deleteRssRuleRequestDto
    });
}

// 批量下载rss条目
export function batchDownloadRssItems(batchDownloadRssItemsRequestDto) {
    return request({
        url: '/fileServer/torrent/batchDownloadRssItems',
        method: 'post',
        data: batchDownloadRssItemsRequestDto
    });
}

// 添加rss订阅
export function addRss(addRssRequestDto) {
    return request({
        url: '/fileServer/torrent/addRss',
        method: 'post',
        data: addRssRequestDto
    });
}

// 更新rss订阅信息
export function updateRss(updateRssRequestDto) {
    return request({
        url: '/fileServer/torrent/updateRss',
        method: 'post',
        data: updateRssRequestDto
    });
}

// 移除rss订阅
export function removeRss(deleteRssRequestDto) {
    return request({
        url: '/fileServer/torrent/removeRss',
        method: 'post',
        data: deleteRssRequestDto
    });
}
