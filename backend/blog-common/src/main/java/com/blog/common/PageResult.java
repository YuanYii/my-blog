package com.blog.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一分页响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    private List<T> records;
    private long total;
    private long page;
    private long size;
    private long totalPages;

    public static <T> PageResult<T> of(List<T> records, long total, long page, long size) {
        long totalPages = size == 0 ? 0 : (total + size - 1) / size;
        return new PageResult<>(records, total, page, size, totalPages);
    }
}
