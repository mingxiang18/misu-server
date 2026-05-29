package com.misu.fileServer.service;

import com.misu.fileServer.domain.dto.SaveTextRequestDto;
import com.misu.fileServer.domain.dto.TextContentResponseDto;

/**
 * 文本编辑相关 Service。
 *
 * <p>从 {@code FileServiceImpl} 拆出的「文本预览 / 编辑」职责：读取文本内容（限 1 MB、UTF-8 解码）、
 * 覆盖保存文本内容（UTF-8）。方法签名 / 逻辑与原 god class 完全一致。</p>
 *
 * @author misu
 */
public interface FileTextService {

    /**
     * 读取文本文件内容（限 1 MB，UTF-8 解码）。
     */
    TextContentResponseDto getTextContent(Integer openType, String filePath);

    /**
     * 覆盖保存文本文件（UTF-8）。
     */
    void saveTextContent(SaveTextRequestDto request);
}
