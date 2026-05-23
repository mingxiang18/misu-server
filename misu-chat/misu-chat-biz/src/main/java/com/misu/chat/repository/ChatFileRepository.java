package com.misu.chat.repository;

import com.misu.chat.domain.entity.ChatFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatFileRepository extends JpaRepository<ChatFile, Long> {

    List<ChatFile> findByConversationIdAndDeletedFalseOrderByCreateTimeDesc(Long conversationId);
}
