package com.misu.fileServer.domain.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BatchOperationResultDtoTest {

    @Test
    void aggregates_success_and_failure_counts_with_details() {
        BatchOperationResultDto r = new BatchOperationResultDto();
        r.addSuccess();
        r.addSuccess();
        r.addFailure("a/b.txt", "不存在");
        r.addFailure("c/d.txt", "目标目录已存在同名文件");

        assertEquals(2, r.getSuccessCount());
        assertEquals(2, r.getFailureCount());
        assertEquals(2, r.getFailures().size());
        assertEquals("a/b.txt", r.getFailures().get(0).getFilePath());
        assertEquals("不存在", r.getFailures().get(0).getMessage());
        assertEquals("c/d.txt", r.getFailures().get(1).getFilePath());
    }
}
