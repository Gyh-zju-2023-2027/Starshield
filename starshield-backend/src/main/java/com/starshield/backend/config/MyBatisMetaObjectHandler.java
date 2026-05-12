package com.starshield.backend.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 字段自动填充处理器。
 * <p>
 * 配合 {@code @TableField(fill = FieldFill.INSERT)} 在写入时自动填充 createTime 等字段，
 * 修复消费链路因 createTime 为空导致 NACK 进 DLQ 的问题。
 */
@Component
public class MyBatisMetaObjectHandler implements MetaObjectHandler {

    private static final String CREATE_TIME = "createTime";
    private static final String UPDATE_TIME = "updateTime";

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        if (metaObject.hasGetter(CREATE_TIME)) {
            strictInsertFill(metaObject, CREATE_TIME, LocalDateTime.class, now);
        }
        if (metaObject.hasGetter(UPDATE_TIME)) {
            strictInsertFill(metaObject, UPDATE_TIME, LocalDateTime.class, now);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        if (metaObject.hasGetter(UPDATE_TIME)) {
            strictUpdateFill(metaObject, UPDATE_TIME, LocalDateTime.class, LocalDateTime.now());
        }
    }
}
