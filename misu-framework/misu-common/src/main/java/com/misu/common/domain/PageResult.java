package com.misu.common.domain;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;

@Data
@NoArgsConstructor
public class PageResult {
    private long total = 0;
    private int totalPages = 0;
    private int pageSize = 0;
    private int pageNumber = 0;
    private List<?> list;

    public static PageResult buildPageResult(Page<?> page) {
        PageResult pageResult = new PageResult();
        pageResult.setTotal(page.getTotalElements());
        pageResult.setTotalPages(page.getTotalPages());
        pageResult.setPageSize(page.getSize());
        pageResult.setPageNumber(page.getNumber());
        pageResult.setList(page.getContent());
        return pageResult;
    }
}
