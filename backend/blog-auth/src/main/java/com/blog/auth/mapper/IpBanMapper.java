package com.blog.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.blog.auth.entity.IpBan;
import org.apache.ibatis.annotations.Mapper;

/**
 * IP 封禁记录 Mapper
 */
@Mapper
public interface IpBanMapper extends BaseMapper<IpBan> {
}
