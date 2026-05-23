package com.misu.chat.service;

import com.misu.chat.domain.dto.FileDto;
import com.misu.chat.domain.entity.ChatFile;
import com.misu.chat.domain.entity.ChatMessage;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface ChatFileService {

    /** 上传文件到磁盘并登记 chat_file，返回元数据（含 id，供消息引用） */
    FileDto saveUploaded(Long conversationId, String uploaderUserId, String senderType,
                         MultipartFile file, String category);

    /** bb 返回的外链文件（netFile）登记进群文件 */
    void indexFromMessage(ChatMessage message);

    /** 群文件列表（含 canDelete 标记：群主或上传者） */
    List<FileDto> listFiles(Long conversationId, String currentUserId, String conversationOwnerId);

    ChatFile getById(Long fileId);

    /** 取下载内容（磁盘文件读字节；netFile 返回 url；旧 base64 消息兜底） */
    FileDownload download(Long fileId);

    /** 删除（软删）：群主或上传者可删；返回是否成功 */
    boolean delete(Long fileId, String currentUserId, String conversationOwnerId);

    /** 下载内容载体：磁盘文件用 bytes，netFile 用 netUrl */
    class FileDownload {
        public String fileName;
        public String mimeType;
        public byte[] bytes;
        public String netUrl;
    }
}
