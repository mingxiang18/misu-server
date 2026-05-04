package com.misu.fileServer.repository;

import com.misu.fileServer.domain.entity.RssItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RssItemRepository extends JpaRepository<RssItem, Long> {

    List<RssItem> findByRssIdOrderByPublishTimeDescCreateTimeDesc(Long rssId);

    Optional<RssItem> findFirstByRssIdAndTorrentHash(Long rssId, String torrentHash);

    Optional<RssItem> findFirstByRssIdAndGuid(Long rssId, String guid);
}
