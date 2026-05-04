import request from '@/api/request'

// 获取磁力链接列表
export function getTorrentList(queryParams) {
    return request({
        url: '/fileServer/torrent/getTorrentList',
        method: 'get',
        params: queryParams
    });
}

// 获取磁力链接详情
export function getTorrentDetail(queryParams) {
    return request({
        url: '/fileServer/torrent/getTorrentDetail',
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

// 批量更新磁力链接
export function batchUpdateUserTorrent(batchUpdateTorrentRequestDto) {
    return request({
        url: '/fileServer/torrent/batchUpdateUserTorrent',
        method: 'post',
        data: batchUpdateTorrentRequestDto
    });
}

// 强制刷新磁力链接状态
export function refreshUserTorrentState(deleteTorrentRequestDto) {
    return request({
        url: '/fileServer/torrent/refreshUserTorrentState',
        method: 'post',
        data: deleteTorrentRequestDto
    });
}

// 删除服务器磁力任务
export function deleteServerTorrent(deleteServerTorrentRequestDto) {
    return request({
        url: '/fileServer/torrent/deleteServerTorrent',
        method: 'post',
        data: deleteServerTorrentRequestDto
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

// 重试同步磁力文件
export function retryUserTorrentSync(deleteTorrentRequestDto) {
    return request({
        url: '/fileServer/torrent/retryUserTorrentSync',
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
