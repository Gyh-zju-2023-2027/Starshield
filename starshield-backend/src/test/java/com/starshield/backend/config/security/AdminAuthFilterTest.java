package com.starshield.backend.config.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAuthFilterTest {

    @Test
    void requiresAuth_adminPaths() {
        assertTrue(AdminAuthFilter.requiresAuth("/api/admin/moderation/pending", "GET"));
        assertTrue(AdminAuthFilter.requiresAuth("/api/admin/moderation/1/confirm-ban", "POST"));
    }

    @Test
    void requiresAuth_controlWritesOnly() {
        assertFalse(AdminAuthFilter.requiresAuth("/api/control/rules/sensitive-words", "GET"));
        assertTrue(AdminAuthFilter.requiresAuth("/api/control/rules/sensitive-words", "PUT"));
        assertTrue(AdminAuthFilter.requiresAuth("/api/control/prompt", "PUT"));
    }

    @Test
    void requiresAuth_reindexAndCrawl() {
        assertTrue(AdminAuthFilter.requiresAuth("/api/archive/reindex", "POST"));
        assertFalse(AdminAuthFilter.requiresAuth("/api/archive/search", "GET"));
        assertTrue(AdminAuthFilter.requiresAuth("/api/crawl/tasks", "GET"));
    }

    @Test
    void requiresAuth_publicPaths() {
        assertFalse(AdminAuthFilter.requiresAuth("/api/chat/upload", "POST"));
        assertFalse(AdminAuthFilter.requiresAuth("/api/dashboard/metrics", "GET"));
        assertFalse(AdminAuthFilter.requiresAuth("/api/archive/search", "GET"));
    }
}
