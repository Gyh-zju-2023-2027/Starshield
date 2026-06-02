package com.starshield.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;

/**
 * 星盾 (StarShield) 舆情监控中台 - 启动入口
 * 海量游戏玩家发言舆情与违规智能监控系统
 */
@SpringBootApplication
public class StarShieldApplication {

    public static void main(String[] args) {
        Environment env = SpringApplication.run(StarShieldApplication.class, args).getEnvironment();
        String mode = env.getProperty("starshield.runtime.mode", "monolith");
        String port = env.getProperty("server.port", "8080");
        System.out.printf("""
                ╔══════════════════════════════════════════════════╗
                ║       星盾 StarShield 舆情监控中台已启动          ║
                ║       端口: %-5s  模式: %-10s              ║
                ╚══════════════════════════════════════════════════╝
                %n""", port, mode);
    }
}
