package com.misu.fileServer.service;

import com.misu.fileServer.domain.dto.FileVersionDto;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.domain.entity.FileVersion;

import java.util.List;
import java.util.Optional;

/**
 * 文件版本服务：在覆盖前打快照、列举、还原、删除。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>大文件门槛：file.version.maxBytesPerSnapshot（默认 50MB）以上不打快照</li>
 *   <li>每文件上限：file.version.maxVersionsPerFile（默认 5），超额淘汰最旧</li>
 *   <li>目录不产生版本（DIRECTORY_FILE 直接跳过）</li>
 *   <li>失败永远不影响主流程（snapshot 抛 RuntimeException 主流程吃掉）</li>
 * </ul>
 */
public interface FileVersionService {

    /** 在覆盖前给 currentMapping 当前的物理文件打一个快照；返回 null 表示因门槛/异常未快照 */
    Optional<FileVersion> snapshotIfEligible(FileMapping currentMapping, String reason);

    /** 列出某 mapping 的所有版本，按 versionNo 倒序 */
    List<FileVersionDto> listVersions(FileMapping currentMapping);

    /** 还原指定版本：当前文件作为新版本入快照表 + 把版本快照内容写回 mapping.targetPath */
    void restoreVersion(Long versionId);

    /** 删除单个版本（仅删一条记录 + 物理快照） */
    void purgeVersion(Long versionId);

    /** mapping 永久删除时级联清版本 */
    void purgeAllVersionsForMapping(Long mappingId);
}
