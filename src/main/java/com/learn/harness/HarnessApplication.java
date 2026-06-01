package com.learn.harness;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Harness 项目主启动类
 * <p>
 * 作用：
 * 1. 作为 Spring Boot 应用的启动入口
 * 2. 触发 Spring AI Alibaba 自动配置（DashScopeChatModel 等）
 * 3. 自动扫描本包及子包下所有 @Component / @Service / @Configuration / @RestController
 */
@SpringBootApplication
public class HarnessApplication {

    /**
     * 程序主入口方法
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(HarnessApplication.class, args);
    }

}
