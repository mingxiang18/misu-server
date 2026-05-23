package com.misu.chat.repository;

import com.misu.chat.domain.entity.ChatConversationMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatConversationMemberRepository extends JpaRepository<ChatConversationMember, Long> {

    List<ChatConversationMember> findByMemberUserId(String memberUserId);

    Optional<ChatConversationMember> findFirstByConversationIdAndMemberUserId(Long conversationId, String memberUserId);

    List<ChatConversationMember> findByConversationId(Long conversationId);

    boolean existsByConversationIdAndMemberUserId(Long conversationId, String memberUserId);

    void deleteByConversationIdAndMemberUserId(Long conversationId, String memberUserId);
}
