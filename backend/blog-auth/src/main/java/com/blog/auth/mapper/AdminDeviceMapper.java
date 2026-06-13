package com.blog.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.auth.entity.AdminDevice;
import org.apache.ibatis.annotations.Mapper;

/**
 * 设备白名单 Mapper
 */
@Mapper
public interface AdminDeviceMapper extends BaseMapper<AdminDevice> {
}
