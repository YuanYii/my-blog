package com.blog.common;

import lombok.Data;

/**
 * 通用分页请求
 */
@Data
public class PageRequest {

    private long page = 1L;
    private long size = 10L;
    private String sortBy;
    private String order = "desc";
}
