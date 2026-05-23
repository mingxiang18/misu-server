package com.misu.chat.service;

import com.misu.chat.domain.dto.FileDto;
import com.misu.chat.domain.entity.ChatFile;
import com.misu.chat.domain.entity.ChatMessage;

import java.util.List;

public interface ChatFileService {

    /** 扫描消息内容，把其中的文件（localFile/netFile）索引进 chat_file */
    void indexFromMessage(ChatMessage message);

    /** 群文件列表（含 canDelete 标记：群主或上传者） */
    List<FileDto> listFiles(Long conversationId, String currentUserId, String conversationOwnerId);

    ChatFile getById(Long fileId);

    /** 取文件下载内容（localFile 解码 base64；netFile 返回 url） */
    FileDownload download(Long fileId);

    /** 删除（软删）：群主或上传者可删；返回是否成功 */
    boolean delete(Long fileId, String currentUserId, String conversationOwnerId);

    /** 下载内容载体：localFile 用 bytes，netFile 用 netUrl */
    class FileDownload {
        public String fileName;
        public String mimeType;
        public byte[] bytes;
        public String netUrl;
    }
}
