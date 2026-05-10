package com.misu.fileServer.domain.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量操作结果，逐项汇报成功/失败。
 */
@Data
@NoArgsConstructor
public class BatchOperationResultDto {

    @ApiModelProperty("成功项数")
    private int successCount;

    @ApiModelProperty("失败项数")
    private int failureCount;

    @ApiModelProperty("失败明细")
    private List<BatchItemError> failures = new ArrayList<>();

    public void addSuccess() {
        this.successCount++;
    }

    public void addFailure(String filePath, String message) {
        this.failureCount++;
        this.failures.add(new BatchItemError(filePath, message));
    }

    @Data
    @NoArgsConstructor
    public static class BatchItemError {
        @ApiModelProperty("文件路径")
        private String filePath;

        @ApiModelProperty("失败原因")
        private String message;

        public BatchItemError(String filePath, String message) {
            this.filePath = filePath;
            this.message = message;
        }
    }
}
