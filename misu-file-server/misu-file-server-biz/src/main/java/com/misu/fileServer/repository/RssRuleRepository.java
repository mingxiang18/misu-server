package com.misu.fileServer.repository;

import com.misu.fileServer.domain.entity.RssRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RssRuleRepository extends JpaRepository<RssRule, Long> {

    List<RssRule> findByRssIdOrderByCreateTimeDesc(Long rssId);

    List<RssRule> findByRssIdAndEnabledTrueOrderByCreateTimeDesc(Long rssId);
}
