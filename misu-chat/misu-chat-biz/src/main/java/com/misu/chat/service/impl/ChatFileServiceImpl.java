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
import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatFileServiceImpl implements ChatFileService {

    private static final String CAT_IMAGE = "image";
    private static final String CAT_FILE = "file";

    @Value("${chat.file.path:}")
    private String configuredPath;

    @Resource
    private ChatFileRepository fileRepository;
    @Resource
    private ChatMessageRepository messageRepository;
    @Resource
    private UserInfoService userInfoService;

    private Path baseDir() {
        String p = StringUtils.hasText(configuredPath)
                ? configuredPath
                : System.getProperty("user.home") + "/.misu-dev/files/chat/";
        return Paths.get(p);
    }

    @Override
    @Transactional("chatTransactionManager")
    public FileDto saveUploaded(Long conversationId, String uploaderUserId, String senderType,
                                MultipartFile file, String category) {
        if (file == null || file.isEmpty()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件为空");
        }
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        String cat = CAT_IMAGE.equals(category) ? CAT_IMAGE : CAT_FILE;
        String ext = "";
        int dot = original.lastIndexOf('.');
        if (dot >= 0) {
            ext = original.substring(dot);
        }
        String diskName = UUID.randomUUID().toString().replace("-", "") + ext;
        String relPath = conversationId + "/" + diskName;
        try {
            Path dir = baseDir().resolve(String.valueOf(conversationId));
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(diskName).toFile());
        } catch (IOException e) {
            log.error("保存群文件失败", e);
            throw new ServiceException(HttpStatus.ERROR, "文件保存失败");
        }

        ChatFile f = new ChatFile();
        f.setConversationId(conversationId);
        f.setUploaderUserId(uploaderUserId);
        f.setSenderType(senderType);
        f.setFileName(original);
        f.setMimeType(file.getContentType());
        f.setSize(file.getSize());
        f.setCategory(cat);
        f.setStorePath(relPath);
        f.setDeleted(false);
        f.setCreateTime(LocalDateTime.now());
        ChatFile saved = fileRepository.save(f);

        FileDto dto = new FileDto();
        dto.setId(saved.getId());
        dto.setFileName(saved.getFileName());
        dto.setMimeType(saved.getMimeType());
        dto.setSize(saved.getSize());
        dto.setCategory(saved.getCategory());
        dto.setSenderType(senderType);
        dto.setUploaderUserId(uploaderUserId);
        dto.setCreateTime(saved.getCreateTime());
        return dto;
    }

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
            // 只索引 bb 返回的外链文件；磁盘文件在上传时已登记
            if (!"netFile".equals(c.getString("type"))) {
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
            f.setCategory(CAT_FILE);
            f.setNetUrl(c.getString("data"));
            f.setSourceType("netFile");
            f.setDeleted(false);
            f.setCreateTime(LocalDateTime.now());
            fileRepository.save(f);
        }
    }

    @Override
    @Transactional(value = "chatTransactionManager", readOnly = true)
    public List<FileDto> listFiles(Long conversationId, String currentUserId, String conversationOwnerId) {
        // 群文件面板只列「文件」，图片在聊天里内联显示不进列表
        List<ChatFile> files = fileRepository.findByConversationIdAndDeletedFalseOrderByCreateTimeDesc(conversationId)
                .stream().filter(f -> !CAT_IMAGE.equals(f.getCategory())).collect(Collectors.toList());
        Set<String> uploaderIds = files.stream()
                .map(ChatFile::getUploaderUserId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        java.util.Map<String, UserBriefDto> userMap = userInfoService.batchGet(uploaderIds);
        boolean isOwner = currentUserId != null && currentUserId.equals(conversationOwnerId);

        return files.stream().map(f -> {
            FileDto dto = new FileDto();
            dto.setId(f.getId());
            dto.setFileName(f.getFileName());
            dto.setMimeType(f.getMimeType());
            dto.setSize(f.getSize());
            dto.setCategory(f.getCategory());
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
        if (f == null || Boolean.TRUE.equals(f.getDeleted())) {
            return null;
        }
        FileDownload d = new FileDownload();
        d.fileName = f.getFileName();
        d.mimeType = f.getMimeType() != null ? f.getMimeType() : "application/octet-stream";

        // 1) 磁盘文件
        if (StringUtils.hasText(f.getStorePath())) {
            try {
                d.bytes = Files.readAllBytes(baseDir().resolve(f.getStorePath()));
                return d;
            } catch (IOException e) {
                log.error("读取磁盘文件失败: {}", f.getStorePath(), e);
                return null;
            }
        }
        // 2) 外链
        if (StringUtils.hasText(f.getNetUrl())) {
            d.netUrl = f.getNetUrl();
            return d;
        }
        // 3) 兼容旧数据：base64 内联在消息 content_json
        if (f.getMessageId() != null) {
            ChatMessage msg = messageRepository.findById(f.getMessageId()).orElse(null);
            if (msg != null && msg.getContentJson() != null) {
                JSONArray arr = JSON.parseArray(msg.getContentJson());
                for (int i = 0; i < arr.size(); i++) {
                    JSONObject c = arr.getJSONObject(i);
                    String fn = c.getString("fileName");
                    if (f.getFileName() != null && f.getFileName().equals(fn)) {
                        String base64 = c.getString("data");
                        d.bytes = base64 != null ? Base64.getDecoder().decode(base64) : new byte[0];
                        return d;
                    }
                }
            }
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
