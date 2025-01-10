package com.misu.fileServer.service;

import com.misu.fileServer.domain.dto.*;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 磁力相关Service
 *
 * @author misu
 */
public interface TorrentService {

    /**
     * 获取磁力链接列表
     */
    Page<UserTorrentDetailDto> getTorrentList(UserTorrentQueryRequestDto userTorrentQueryRequestDto);

    /**
     * 获取rss订阅列表
     */
    Page<RssInfoDto> getRssList(RssQueryRequestDto rssQueryRequestDto);

    /**
     * 获取指定rss订阅详情
     */
    RssDetailDto getRssDetail(RssDetailRequestDto rssDetailRequestDto);

    /**
     * 添加磁力链接
     */
    void addUserTorrent(AddTorrentRequestDto addTorrentRequestDto);

    /**
     * 更新磁力链接
     */
    void updateUserTorrent(UpdateTorrentRequestDto updateTorrentRequestDto);

    /**
     * 移除磁力链接
     */
    void removeUserTorrent(DeleteTorrentRequestDto deleteTorrentRequestDto);

    /**
     * 添加rss订阅
     */
    void addRss(AddRssRequestDto addRssRequestDto);

    /**
     * 更新rss订阅信息
     */
    void updateRss(UpdateRssRequestDto updateRssRequestDto);

    /**
     * 移除rss订阅
     */
    void removeRss(DeleteRssRequestDto deleteRssRequestDto);

    /**
     * 从qBitTorrent获取并更新未完成的torrent状态
     */
    void updateNotCompletedTorrentState();

    /**
     * 将qBitTorrent已经完成下载的文件同步到用户目录
     */
    void moveCompletedTorrentToUserDirectory();

    /**
     * 定时更新rss订阅状态
     */
    void updateRssStateSchedule();
}
