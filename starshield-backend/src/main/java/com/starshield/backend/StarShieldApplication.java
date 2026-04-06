package com.starshield.backend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 星盾 (StarShield) 舆情监控中台 - 启动入口
 * 海量游戏玩家发言舆情与违规智能监控系统
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.starshield.backend.mapper")
public class StarShieldApplication {

    public static void main(String[] args) {
        SpringApplication.run(StarShieldApplication.class, args);
        System.out.println("""
                ╔══════════════════════════════════════════════════╗
                ║       星盾 StarShield 舆情监控中台已启动          ║
                ║       端口: 8080  环境: 本地开发                  ║
                ╚══════════════════════════════════════════════════╝
                """);
    }
}
