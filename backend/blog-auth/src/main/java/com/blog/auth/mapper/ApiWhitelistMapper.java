package com.blog.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.auth.entity.ApiWhitelist;
import org.apache.ibatis.annotations.Mapper;

/**
 * API 白名单 Mapper
 */
@Mapper
public interface ApiWhitelistMapper extends BaseMapper<ApiWhitelist> {
}
