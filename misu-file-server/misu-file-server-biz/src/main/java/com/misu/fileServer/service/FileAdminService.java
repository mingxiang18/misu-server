package com.misu.fileServer.service;

import com.misu.fileServer.domain.dto.FileResponseDto;
import com.misu.fileServer.domain.dto.ShareStagingRequestDto;
import com.misu.fileServer.domain.dto.StagingEntryDto;
import com.misu.fileServer.domain.dto.StorageUsageResponseDto;

import java.util.List;
import java.util.Map;

/**
 * 文件服务的「管理员 / staging / 回填触发」Service。
 *
 * <p>从 {@code FileServiceImpl} 抽出的、仅管理员（ADMIN / FILE_ADMIN）能调用的运维类能力：
 * 管理员视角浏览任意用户文件 / 用量、staging 物理落地区的浏览与共享、file_mapping 物理回填触发。
 * 方法签名 / 逻辑 / 权限语义与原 god class 完全一致。</p>
 *
 * @author misu
 */
public interface FileAdminService {

    /**
     * 管理员视角：列出指定用户在指定 openType / parentPath 下的文件。openType=1 公共时 userId 自动归一为 "public"。
     */
    List<FileResponseDto> listFilesAsAdmin(Integer openType, String userId, String parentPath);

    /**
     * 管理员视角：查看指定用户在指定 openType 下的存储用量。
     */
    StorageUsageResponseDto getStorageUsageAsAdmin(Integer openType, String userId);

    /**
     * 获取 staging 物理根目录（管理员维护用的物理目录入口）。
     */
    String getStagingRoot();

    /**
     * 列出 staging 物理目录下的条目，不依赖 file_mapping，按目录优先 + 文件名升序排序。
     */
    List<StagingEntryDto> listStaging(String subPath);

    /**
     * 把 staging 物理文件 / 目录共享到公共目录。
     */
    void shareStagingToPublic(ShareStagingRequestDto request);

    /**
     * 把 staging 物理文件 / 目录共享到指定用户的私人目录。
     */
    void shareStagingToUser(ShareStagingRequestDto request);

    /**
     * 管理员触发 file_mapping 回填任务（异步）。
     */
    void startFileMappingBackfill();

    /**
     * 获取 file_mapping 回填任务状态。
     */
    Map<String, Object> getFileMappingBackfillStatus();
}
