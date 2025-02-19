import request from '@/api/request'

// 获取磁力链接列表
export function getTorrentList(queryParams) {
    return request({
        url: '/fileServer/torrent/getTorrentList',
        method: 'get',
        params: queryParams
    });
}

// 获取rss订阅列表
export function getRssList(queryParams) {
    return request({
        url: '/fileServer/torrent/getRssList',
        method: 'get',
        params: queryParams
    });
}

// 添加磁力链接
export function addUserTorrent(addTorrentRequestDto) {
    return request({
        url: '/fileServer/torrent/addUserTorrent',
        method: 'post',
        data: addTorrentRequestDto
    });
}

// 更新磁力链接
export function updateUserTorrent(updateTorrentRequestDto) {
    return request({
        url: '/fileServer/torrent/updateUserTorrent',
        method: 'post',
        data: updateTorrentRequestDto
    });
}

// 移除磁力链接
export function removeUserTorrent(deleteTorrentRequestDto) {
    return request({
        url: '/fileServer/torrent/removeUserTorrent',
        method: 'post',
        data: deleteTorrentRequestDto
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