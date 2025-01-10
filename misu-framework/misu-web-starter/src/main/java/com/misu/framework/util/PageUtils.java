package com.misu.framework.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 表格数据处理
 *
 * @author misuaa
 */
public class PageUtils {
    /**
     * 当前记录起始索引
     */
    public static final String PAGE_NUM = "pageNum";

    /**
     * 每页显示记录数
     */
    public static final String PAGE_SIZE = "pageSize";

    /**
     * 排序列
     */
    public static final String ORDER_BY_COLUMN = "orderByColumn";

    /**
     * 排序的方向 "desc" 或者 "asc".
     */
    public static final String IS_ASC = "isAsc";

    /**
     * 封装分页对象
     */
    public static PageRequest getPageRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest httpServletRequest = attributes.getRequest();
        //分页的pageNumber需要-1
        int pageNum = convertInt(httpServletRequest.getParameter(PAGE_NUM), 1) - 1;
        int pageSize = convertInt(httpServletRequest.getParameter(PAGE_SIZE), 10);

        // 处理参数
        return PageRequest.of(pageNum, pageSize);
    }

    /**
     * 字符串转int
     */
    private static Integer convertInt(String value, Integer defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
