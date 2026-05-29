package com.misu.chat.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.bb.bot.entity.bb.BbMessageContent;
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
import com.misu.framework.upload.ChunkedUploadAssembler;
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
import java.util.ArrayList;
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
    private static final String CHUNK_DIR = "_chunks";

    @Value("${chat.file.path:}")
    private String configuredPath;

    @Resource
    private ChatFileRepository fileRepository;
    @Resource
    private ChatMessageRepository messageRepository;
    @Resource
    private UserInfoService userInfoService;
    @Resource
    private ChunkedUploadAssembler chunkedUploadAssembler;

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

        return buildDto(saved, senderType, uploaderUserId);
    }

    @Override
    @Transactional("chatTransactionManager")
    public ChunkUploadResult saveUploadedChunk(Long conversationId, String uploaderUserId, String senderType,
                                               String uploadId, MultipartFile chunk, int chunkIndex, int totalChunks,
                                               String fileName, String mimeType, Long fileSize, String category) {
        if (chunk == null || chunk.isEmpty()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "分片为空");
        }
        if (!StringUtils.hasText(uploadId) || totalChunks <= 0 || chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "分片参数不合法");
        }
        // uploadId 前端生成，清洗防目录穿越
        String safeUploadId = uploadId.replaceAll("[^a-zA-Z0-9_-]", "");
        if (safeUploadId.isEmpty()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "uploadId 不合法");
        }
        Path chunkDir = baseDir().resolve(CHUNK_DIR).resolve(safeUploadId);
        chunkedUploadAssembler.storeChunk(chunkDir, chunkIndex, chunk);

        String original = StringUtils.hasText(fileName) ? fileName : "file";
        String cat = CAT_IMAGE.equals(category) ? CAT_IMAGE : CAT_FILE;
        String ext = "";
        int dot = original.lastIndexOf('.');
        if (dot >= 0) {
            ext = original.substring(dot);
        }
        String diskName = UUID.randomUUID().toString().replace("-", "") + ext;
        String relPath = conversationId + "/" + diskName;
        Path finalFile = baseDir().resolve(String.valueOf(conversationId)).resolve(diskName);

        long mergedSize = chunkedUploadAssembler.mergeIfComplete(safeUploadId, chunkDir, totalChunks, finalFile);
        if (mergedSize < 0) {
            // 未到齐
            return new ChunkUploadResult(false, null);
        }

        ChatFile f = new ChatFile();
        f.setConversationId(conversationId);
        f.setUploaderUserId(uploaderUserId);
        f.setSenderType(senderType);
        f.setFileName(original);
        f.setMimeType(mimeType);
        f.setSize(fileSize != null ? fileSize : mergedSize);
        f.setCategory(cat);
        f.setStorePath(relPath);
        f.setDeleted(false);
        f.setCreateTime(LocalDateTime.now());
        ChatFile saved = fileRepository.save(f);
        return new ChunkUploadResult(true, buildDto(saved, senderType, uploaderUserId));
    }

    private FileDto buildDto(ChatFile saved, String senderType, String uploaderUserId) {
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
    @Transactional("chatTransactionManager")
    public List<BbMessageContent> referenceBotInlineAttachments(Long conversationId, List<BbMessageContent> content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        List<BbMessageContent> out = new ArrayList<>(content.size());
        for (BbMessageContent c : content) {
            String t = c.getType();
            boolean isImage = "localImage".equals(t);
            boolean isFile = "localFile".equals(t);
            if ((isImage || isFile) && c.getData() != null) {
                try {
                    Long id = saveBotInline(conversationId, c.getData().toString(),
                            c.getFileName(), c.getMimeType(), isImage ? CAT_IMAGE : CAT_FILE);
                    out.add(BbMessageContent.builder()
                            .type(isImage ? "chatImage" : "chatFile")
                            .data(id)
                            .fileName(c.getFileName())
                            .mimeType(c.getMimeType())
                            .size(c.getSize())
                            .build());
                } catch (Exception e) {
                    log.warn("bb 内联附件存盘失败，保留原始内容 type={}", t, e);
                    out.add(c);
                }
            } else {
                out.add(c);
            }
        }
        return out;
    }

    /** 把 bb 返回的 base64（可能带 data:;base64, 前缀）解码存盘并登记 chat_file（senderType=BOT），返回 fileId */
    private Long saveBotInline(Long conversationId, String base64Data, String fileName, String mimeType, String category)
            throws IOException {
        String b64 = base64Data;
        if (b64.startsWith("data:")) {
            int comma = b64.indexOf(',');
            if (comma >= 0) {
                b64 = b64.substring(comma + 1);
            }
        }
        byte[] bytes = Base64.getDecoder().decode(b64);
        String original = StringUtils.hasText(fileName) ? fileName : (CAT_IMAGE.equals(category) ? "图片" : "文件");
        String ext = "";
        int dot = original.lastIndexOf('.');
        if (dot >= 0) {
            ext = original.substring(dot);
        }
        String diskName = UUID.randomUUID().toString().replace("-", "") + ext;
        String relPath = conversationId + "/" + diskName;
        Path dir = baseDir().resolve(String.valueOf(conversationId));
        Files.createDirectories(dir);
        Files.write(dir.resolve(diskName), bytes);

        ChatFile f = new ChatFile();
        f.setConversationId(conversationId);
        f.setSenderType("BOT");
        f.setFileName(original);
        f.setMimeType(mimeType);
        f.setSize((long) bytes.length);
        f.setCategory(category);
        f.setStorePath(relPath);
        f.setDeleted(false);
        f.setCreateTime(LocalDateTime.now());
        return fileRepository.save(f).getId();
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

        // 1) 磁盘文件：流式（不预读字节），由调用方按需 stream
        if (StringUtils.hasText(f.getStorePath())) {
            java.io.File diskFile = baseDir().resolve(f.getStorePath()).toFile();
            if (!diskFile.exists()) {
                log.error("磁盘文件不存在: {}", f.getStorePath());
                return null;
            }
            d.diskFile = diskFile;
            return d;
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
