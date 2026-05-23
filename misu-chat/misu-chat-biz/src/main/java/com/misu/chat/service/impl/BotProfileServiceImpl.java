package com.misu.chat.service.impl;

import com.misu.chat.domain.dto.BotProfileDto;
import com.misu.chat.domain.entity.ChatBotProfile;
import com.misu.chat.repository.ChatBotProfileRepository;
import com.misu.chat.service.BotProfileService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class BotProfileServiceImpl implements BotProfileService {

    private static final Long SINGLE_ID = 1L;
    private static final String DEFAULT_NAME = "冥想bb";

    @Resource
    private ChatBotProfileRepository repository;

    @Override
    @Transactional("chatTransactionManager")
    public BotProfileDto getProfile() {
        ChatBotProfile p = loadOrCreate();
        BotProfileDto dto = new BotProfileDto();
        dto.setName(p.getName());
        dto.setAvatar(p.getAvatar());
        return dto;
    }

    @Override
    @Transactional("chatTransactionManager")
    public void updateAvatar(String avatarDataUrl) {
        ChatBotProfile p = loadOrCreate();
        p.setAvatar(avatarDataUrl);
        p.setUpdateTime(LocalDateTime.now());
        repository.save(p);
    }

    private ChatBotProfile loadOrCreate() {
        return repository.findById(SINGLE_ID).orElseGet(() -> {
            ChatBotProfile p = new ChatBotProfile();
            p.setId(SINGLE_ID);
            p.setName(DEFAULT_NAME);
            p.setUpdateTime(LocalDateTime.now());
            return repository.save(p);
        });
    }
}
