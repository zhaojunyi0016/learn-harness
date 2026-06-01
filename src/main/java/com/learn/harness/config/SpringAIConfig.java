package com.learn.harness.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI Alibaba 配置类
 * <p>
 * 作用：
 * 1. 把 Spring AI 提供的 {@link ChatClient} 注册成 Spring Bean，
 *    供 AgentLoopEngine 等业务组件直接 @Resource 注入使用
 * 2. {@link ChatModel} 由 spring-ai-alibaba-starter-dashscope 自动装配（默认就是 DashScopeChatModel）
 */
@Configuration
public class SpringAIConfig {

    /**
     * 创建并暴露 ChatClient Bean
     * <p>
     * ChatClient 是 Spring AI 的高级对话客户端，封装了：
     * - 消息构建（prompt / messages）
     * - 工具调用（toolCallbacks）
     * - 响应解析（call / stream）
     *
     * @param chatModel Spring 容器中的底层模型实现（默认由 DashScope starter 自动注入）
     * @return 构建好的 ChatClient 实例
     */
    @Bean
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }
}
