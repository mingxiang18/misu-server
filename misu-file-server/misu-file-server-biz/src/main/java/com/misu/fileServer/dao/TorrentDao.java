package com.misu.fileServer.dao;

import com.misu.fileServer.domain.dto.*;
import com.misu.fileServer.domain.entity.RssInfo;
import com.misu.fileServer.domain.entity.TorrentInfo;
import com.misu.fileServer.domain.entity.TorrentUserRelation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

/**
 * 磁力文件管理数据层
 */
public interface TorrentDao {

    /**
     * 通过hash查询磁力信息
     */
    Optional<TorrentInfo> selectTorrentInfoByHash(String torrentHash);

    /**
     * 查询磁力信息
     */
    List<TorrentInfoDto> selectTorrentInfoList(TorrentInfoQueryRequestDto torrentInfoQueryRequestDto);

    /**
     * 查询用户所属的磁力信息
     */
    List<UserTorrentDetailDto> selectUserTorrent(UserTorrentQueryRequestDto userTorrentQueryRequestDto);

    /**
     * 分页查询用户所属的磁力信息
     */
    Page<UserTorrentDetailDto> selectUserTorrentByPage(UserTorrentQueryRequestDto userTorrentQueryRequestDto, PageRequest pageRequest);

    /**
     * 查询rss订阅列表
     */
    List<RssInfoDto> selectRssList(RssQueryRequestDto rssQueryRequestDto);

    /**
     * 分页查询rss订阅列表
     */
    Page<RssInfoDto> selectRssListByPage(RssQueryRequestDto rssQueryRequestDto, PageRequest pageRequest);

    /**
     * 保存磁力信息
     */
    void saveTorrentInfo(TorrentInfo torrentInfo);

    /**
     * 更新磁力信息
     */
    long updateTorrentInfo(TorrentInfo entity);

    /**
     * 更新磁力状态
     */
    long updateTorrentState(String torrentHash, int state, String remark);

    /**
     * 保存用户与磁力链接关联信息
     */
    void saveTorrentUserRelation(TorrentUserRelation torrentUserRelation);

    /**
     * 更新用户与磁力链接关联信息
     */
    long updateTorrentUserRelation(TorrentUserRelation entity);

    /**
     * 删除用户与磁力链接关联信息
     */
    void deleteTorrentUserRelation(Long userTorrentId);

    /**
     * 保存rss链接
     */
    void saveRssInfo(RssInfo rssInfo);

    /**
     * 更新rss链接
     */
    long updateRssInfo(RssInfo entity);

    /**
     * 更新rss链接
     */
    void deleteRssInfo(Long id);
}
