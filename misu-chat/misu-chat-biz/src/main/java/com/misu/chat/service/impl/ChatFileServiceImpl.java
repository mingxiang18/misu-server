package com.misu.chat.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.misu.account.dto.UserBriefDto;
import com.misu.chat.domain.dto.FileDto;
import com.misu.chat.domain.entity.ChatFile;
import com.misu.chat.domain.entity.ChatMessage;
import com.misu.chat.repository.ChatFileRepository;
import com.misu.chat.repository.ChatMessageRepository;
import com.misu.chat.service.ChatFileService;
import com.misu.chat.service.UserInfoService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatFileServiceImpl implements ChatFileService {

    private static final String TYPE_LOCAL_FILE = "localFile";
    private static final String TYPE_NET_FILE = "netFile";

    @Resource
    private ChatFileRepository fileRepository;
    @Resource
    private ChatMessageRepository messageRepository;
    @Resource
    private UserInfoService userInfoService;

    @Override
    @Transactional("chatTransactionManager")
    public void indexFromMessage(ChatMessage message) {
        if (message == null || message.getContentJson() == null) {
            return;
        }
        JSONArray arr;
        try {
            arr = JSON.parseArray(message.getContentJson());
        } catch (Exception e) {
            return;
        }
        if (arr == null) {
            return;
        }
        for (int i = 0; i < arr.size(); i++) {
            JSONObject c = arr.getJSONObject(i);
            String type = c.getString("type");
            if (!TYPE_LOCAL_FILE.equals(type) && !TYPE_NET_FILE.equals(type)) {
                continue;
            }
            ChatFile f = new ChatFile();
            f.setConversationId(message.getConversationId());
            f.setMessageId(message.getId());
            f.setUploaderUserId(message.getSenderUserId());
            f.setSenderType(message.getSenderType());
            f.setFileName(c.getString("fileName") != null ? c.getString("fileName") : "文件");
            f.setMimeType(c.getString("mimeType"));
            f.setSize(c.getLong("size"));
            f.setSourceType(type);
            f.setDeleted(false);
            f.setCreateTime(LocalDateTime.now());
            fileRepository.save(f);
        }
    }

    @Override
    @Transactional(value = "chatTransactionManager", readOnly = true)
    public List<FileDto> listFiles(Long conversationId, String currentUserId, String conversationOwnerId) {
        List<ChatFile> files = fileRepository.findByConversationIdAndDeletedFalseOrderByCreateTimeDesc(conversationId);
        Set<String> uploaderIds = files.stream()
                .map(ChatFile::getUploaderUserId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        Map<String, UserBriefDto> userMap = userInfoService.batchGet(uploaderIds);
        boolean isOwner = currentUserId != null && currentUserId.equals(conversationOwnerId);

        return files.stream().map(f -> {
            FileDto dto = new FileDto();
            dto.setId(f.getId());
            dto.setFileName(f.getFileName());
            dto.setMimeType(f.getMimeType());
            dto.setSize(f.getSize());
            dto.setSenderType(f.getSenderType());
            dto.setUploaderUserId(f.getUploaderUserId());
            dto.setCreateTime(f.getCreateTime());
            if ("BOT".equals(f.getSenderType())) {
                dto.setUploaderName("冥想bb");
            } else {
                UserBriefDto u = userMap.get(f.getUploaderUserId());
                dto.setUploaderName(u != null ? (u.getNickName() != null ? u.getNickName() : u.getUserName()) : f.getUploaderUserId());
            }
            dto.setCanDelete(isOwner || (currentUserId != null && currentUserId.equals(f.getUploaderUserId())));
            return dto;
        }).collect(Collectors.toList());
    }

    @Override
    @Transactional(value = "chatTransactionManager", readOnly = true)
    public ChatFile getById(Long fileId) {
        return fileRepository.findById(fileId).orElse(null);
    }

    @Override
    @Transactional(value = "chatTransactionManager", readOnly = true)
    public FileDownload download(Long fileId) {
        ChatFile f = fileRepository.findById(fileId).orElse(null);
        if (f == null || Boolean.TRUE.equals(f.getDeleted()) || f.getMessageId() == null) {
            return null;
        }
        ChatMessage msg = messageRepository.findById(f.getMessageId()).orElse(null);
        if (msg == null || msg.getContentJson() == null) {
            return null;
        }
        JSONArray arr = JSON.parseArray(msg.getContentJson());
        for (int i = 0; i < arr.size(); i++) {
            JSONObject c = arr.getJSONObject(i);
            String type = c.getString("type");
            if (!f.getSourceType().equals(type)) {
                continue;
            }
            // 同消息可能多文件：用 fileName 进一步匹配
            String fn = c.getString("fileName");
            if (f.getFileName() != null && fn != null && !f.getFileName().equals(fn)) {
                continue;
            }
            FileDownload d = new FileDownload();
            d.fileName = f.getFileName();
            d.mimeType = f.getMimeType() != null ? f.getMimeType() : "application/octet-stream";
            if (TYPE_NET_FILE.equals(type)) {
                d.netUrl = c.getString("data");
            } else {
                String base64 = c.getString("data");
                d.bytes = base64 != null ? Base64.getDecoder().decode(base64) : new byte[0];
            }
            return d;
        }
        return null;
    }

    @Override
    @Transactional("chatTransactionManager")
    public boolean delete(Long fileId, String currentUserId, String conversationOwnerId) {
        ChatFile f = fileRepository.findById(fileId).orElse(null);
        if (f == null) {
            return false;
        }
        boolean allowed = currentUserId != null
                && (currentUserId.equals(f.getUploaderUserId()) || currentUserId.equals(conversationOwnerId));
        if (!allowed) {
            return false;
        }
        f.setDeleted(true);
        fileRepository.save(f);
        return true;
    }
}
