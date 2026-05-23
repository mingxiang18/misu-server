package com.misu.chat.repository;

import com.misu.chat.domain.entity.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {

    Optional<ChatConversation> findFirstByOwnerUserIdAndType(String ownerUserId, String type);

    List<ChatConversation> findByIdInOrderByLastMessageAtDesc(Collection<Long> ids);
}
