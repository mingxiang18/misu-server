package com.misu.chat.repository;

import com.misu.chat.domain.entity.ChatBotProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatBotProfileRepository extends JpaRepository<ChatBotProfile, Long> {
}
