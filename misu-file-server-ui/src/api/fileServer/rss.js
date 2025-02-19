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