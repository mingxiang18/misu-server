package com.misu.chat.repository;

import com.misu.chat.domain.entity.ChatConversationMember;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatConversationMemberRepository extends JpaRepository<ChatConversationMember, Long> {

    List<ChatConversationMember> findByMemberUserId(String memberUserId);

    List<ChatConversationMember> findByConversationId(Long conversationId);

    boolean existsByConversationIdAndMemberUserId(Long conversationId, String memberUserId);

    void deleteByConversationIdAndMemberUserId(Long conversationId, String memberUserId);
}
