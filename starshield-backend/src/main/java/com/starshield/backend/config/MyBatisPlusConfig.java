package com.starshield.backend.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 自动填充配置
 * <p>
 * 在插入数据时自动填充 createTime 字段，
 * 无需在业务代码中手动设置时间，减少重复代码。
 */
@Component
public class MyBatisPlusConfig implements MetaObjectHandler {

    /**
     * 插入时自动填充
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        // 自动填充 createTime 字段为当前时间
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
    }

    /**
     * 更新时自动填充（当前暂无更新字段，预留）
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        // 预留：如后续添加 updateTime 字段，在此处填充
    }
}
