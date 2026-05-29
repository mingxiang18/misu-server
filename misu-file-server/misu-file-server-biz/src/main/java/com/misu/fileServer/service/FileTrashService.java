package com.misu.fileServer.service;

import com.misu.fileServer.domain.dto.PageResponseDto;
import com.misu.fileServer.domain.dto.TrashFileResponseDto;

/**
 * 回收站相关 Service。
 *
 * <p>从 {@code FileServiceImpl} 拆出的「回收站」职责：回收站列表、还原、永久删除（含物理文件 /
 * 版本快照的 GC 联动）。方法签名 / 逻辑与原 god class 完全一致。</p>
 *
 * @author misu
 */
public interface FileTrashService {

    /**
     * 回收站列表（已逻辑删除项），按删除时间倒序。
     */
    PageResponseDto<TrashFileResponseDto> listTrash(Integer openType, Integer pageNumber, Integer pageSize);

    /**
     * 从回收站还原指定 mapping。
     */
    void restoreFromTrash(Long id);

    /**
     * 永久删除回收站中的某条 mapping（含物理文件，若无其他 active 映射引用）。
     */
    void purgeFromTrash(Long id);
}
