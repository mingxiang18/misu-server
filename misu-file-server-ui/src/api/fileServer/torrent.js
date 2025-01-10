import request from '@/api/request'

// 获取磁力链接列表
export function getTorrentList(queryParams) {
    return request({
        url: '/torrent/getTorrentList',
        method: 'get',
        params: queryParams
    });
}

// 获取rss订阅列表
export function getRssList(queryParams) {
    return request({
        url: '/torrent/getRssList',
        method: 'get',
        params: queryParams
    });
}

// 添加磁力链接
export function addUserTorrent(addTorrentRequestDto) {
    return request({
        url: '/torrent/addUserTorrent',
        method: 'post',
        data: addTorrentRequestDto
    });
}

// 更新磁力链接
export function updateUserTorrent(updateTorrentRequestDto) {
    return request({
        url: '/torrent/updateUserTorrent',
        method: 'post',
        data: updateTorrentRequestDto
    });
}

// 移除磁力链接
export function removeUserTorrent(deleteTorrentRequestDto) {
    return request({
        url: '/torrent/removeUserTorrent',
        method: 'post',
        data: deleteTorrentRequestDto
    });
}

// 添加rss订阅
export function addRss(addRssRequestDto) {
    return request({
        url: '/torrent/addRss',
        method: 'post',
        data: addRssRequestDto
    });
}

// 更新rss订阅信息
export function updateRss(updateRssRequestDto) {
    return request({
        url: '/torrent/updateRss',
        method: 'post',
        data: updateRssRequestDto
    });
}

// 移除rss订阅
export function removeRss(deleteRssRequestDto) {
    return request({
        url: '/torrent/removeRss',
        method: 'post',
        data: deleteRssRequestDto
    });
}