package com.misu.chat.repository;

import com.misu.chat.domain.entity.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /** 幂等用：同一会话同一前端 messageId 只入库一次（bot_offline 自动重发不重复落库） */
    Optional<ChatMessage> findFirstByConversationIdAndClientMessageId(Long conversationId, String clientMessageId);

    /**
     * 按会话倒序（最新在前）游标分页：beforeId 为 null 取最新一页，否则取 id 更小的一页。
     * 调用方拿到后翻转为正序展示。
     */
    @Query("SELECT m FROM ChatMessage m WHERE m.conversationId = :cid "
            + "AND (:beforeId IS NULL OR m.id < :beforeId) ORDER BY m.id DESC")
    List<ChatMessage> pageHistory(@Param("cid") Long conversationId,
                                  @Param("beforeId") Long beforeId,
                                  Pageable pageable);
}
