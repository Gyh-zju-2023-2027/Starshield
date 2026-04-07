package com.starshield.backend;

import com.starshield.backend.common.Result;
import com.starshield.backend.service.DashboardControllerSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：MyBatis-Plus BaseMapper 需由 mybatis-plus-spring-boot3-starter 装配，否则 selectCount 不可用。
 */
@SpringBootTest
class DashboardMetricsSmokeTest {

    @Autowired
    DashboardControllerSupport dashboardControllerSupport;

    @Test
    void metricsShouldSucceed() {
        Result<Map<String, Object>> r = dashboardControllerSupport.metrics();
        assertEquals(200, r.getCode());
        assertNotNull(r.getData());
        assertTrue(((Number) r.getData().get("total")).longValue() >= 0L);
    }
}
