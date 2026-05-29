package com.misu.fileServer.service;

import java.util.Map;

/**
 * 文件服务的「后台维护」Service。
 *
 * <p>从 {@code FileServiceImpl} 抽出的、与单次请求无关的后台工作：
 * 定时清理（过期临时文件 / 逻辑删除映射的物理 GC）以及 file_mapping 物理回填。
 * 实现类必须仍是 Spring bean，使内部 {@code @Scheduled} 定时任务生效。</p>
 *
 * <p>对外仅暴露回填的触发 / 状态查询；定时任务由实现类自管，不进接口。
 * {@code FileService} 的 {@code startFileMappingBackfill} /
 * {@code getFileMappingBackfillStatus} 委托到这里，保持原异步语义与权限语义不变。</p>
 *
 * @author misu
 */
public interface FileMaintenanceService {

    /**
     * 异步触发 file_mapping 物理回填。已有任务在跑时不重入，重复触发抛
     * {@link com.misu.common.exception.ServiceException}（BAD_REQUEST）。
     * 异步执行复用 file-server 的 {@code fileExecutor}，保持原行为。
     */
    void runBackfillAsync();

    /**
     * 回填任务的运行状态与计数（running/startTime/endTime/lastError/
     * processedCount/createdCount/updatedCount）。
     */
    Map<String, Object> getBackfillStatus();
}
