package com.misu.fileServer.schedule;

import com.misu.fileServer.service.TorrentService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * torrent相关定时器
 */
@EnableScheduling
@Component
@Slf4j
public class TorrentSchedule {

    @Resource
    private TorrentService torrentService;

    /**
     * 从qBitTorrent获取并更新未完成的torrent状态
     * 每5分钟更新一次torrent下载状态
     **/
    @Scheduled(cron = "${torrent.updateState:0 0/5 * * * ?}")
    public void updateNotCompletedTorrentState() {
        torrentService.updateNotCompletedTorrentState();
        torrentService.moveCompletedTorrentToUserDirectory();
    }

    /**
     * 更新rss订阅状态
     * 每5分钟更新一次
     **/
    @Scheduled(cron = "${rss.updateState:0 0/5 * * * ?}")
    public void updateRssState() {
        torrentService.updateRssStateSchedule();
    }
}
