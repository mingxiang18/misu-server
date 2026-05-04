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
     * 获取磁力链接详情
     */
    UserTorrentDetailDto getTorrentDetail(DeleteTorrentRequestDto deleteTorrentRequestDto);

    /**
     * 获取rss订阅列表
     */
    Page<RssInfoDto> getRssList(RssQueryRequestDto rssQueryRequestDto);

    /**
     * 获取指定rss订阅详情
     */
    RssDetailDto getRssDetail(RssDetailRequestDto rssDetailRequestDto);

    /**
     * 获取RSS条目历史
     */
    Page<RssItemDto> getRssItems(RssItemQueryRequestDto rssItemQueryRequestDto);

    /**
     * 手动刷新RSS订阅
     */
    void refreshRss(RssDetailRequestDto rssDetailRequestDto);

    /**
     * 获取RSS规则列表
     */
    List<RssRuleDto> getRssRuleList(RssDetailRequestDto rssDetailRequestDto);

    /**
     * 添加RSS规则
     */
    void addRssRule(AddRssRuleRequestDto addRssRuleRequestDto);

    /**
     * 更新RSS规则
     */
    void updateRssRule(UpdateRssRuleRequestDto updateRssRuleRequestDto);

    /**
     * 删除RSS规则
     */
    void removeRssRule(DeleteRssRuleRequestDto deleteRssRuleRequestDto);

    /**
     * 批量下载RSS条目
     */
    void batchDownloadRssItems(BatchDownloadRssItemsRequestDto batchDownloadRssItemsRequestDto);

    /**
     * 添加磁力链接
     */
    void addUserTorrent(AddTorrentRequestDto addTorrentRequestDto);

    /**
     * 更新磁力链接
     */
    void updateUserTorrent(UpdateTorrentRequestDto updateTorrentRequestDto);

    /**
     * 批量更新磁力链接
     */
    void batchUpdateUserTorrent(BatchUpdateTorrentRequestDto batchUpdateTorrentRequestDto);

    /**
     * 强制刷新磁力链接状态
     */
    UserTorrentDetailDto refreshUserTorrentState(DeleteTorrentRequestDto deleteTorrentRequestDto);

    /**
     * 删除服务器磁力任务
     */
    void deleteServerTorrent(DeleteServerTorrentRequestDto deleteServerTorrentRequestDto);

    /**
     * 移除磁力链接
     */
    void removeUserTorrent(DeleteTorrentRequestDto deleteTorrentRequestDto);

    /**
     * 重试同步已完成下载的磁力文件到用户目录
     */
    void retryUserTorrentSync(DeleteTorrentRequestDto deleteTorrentRequestDto);

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
