package com.blog.settings.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.settings.entity.SiteSettings;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站点设置 Mapper
 */
@Mapper
public interface SiteSettingsMapper extends BaseMapper<SiteSettings> {
}
