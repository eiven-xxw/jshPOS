package com.jingshanghui.pos.saas.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.jingshanghui.pos.saas.infrastructure.persistence.entity.SaasPlanEntity;

/** SaaS 套餐简单 CRUD Mapper；复杂状态查询必须进入 XML。 */
@InterceptorIgnore(tenantLine = "true", dataPermission = "true")
public interface SaasPlanMapper extends BaseMapper<SaasPlanEntity> { }
