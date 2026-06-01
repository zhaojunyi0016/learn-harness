package com.learn.harness;

import com.learn.harness.core.AgentLoopEngine;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试控制器
 * <p>
 * 作用：
 * 1. 提供 HTTP 接口，作为前端 / Postman 调用 Agent 的入口
 * 2. 所有用户请求最终都会进入 {@link AgentLoopEngine#agentLoop(String)}，
 *    由 Agent 自主决定是否调用工具、如何回答
 * <p>
 * 示例：
 *   GET /test/chat?userInput=你是谁
 *   GET /test/weather/agent?query=北京今天天气怎么样
 */
@RestController
@RequestMapping("/test")
public class TestController {

    /** Agent 循环引擎，整个项目对外暴露的统一调用入口 */
    @Resource
    private AgentLoopEngine agentLoopEngine;

    /**
     * 通用 Agent 对话测试接口
     * <p>
     * 不论问什么问题，都会走 AgentLoop 流程：
     * 模型判断 → 是否调用工具 → 整合结果 → 返回最终答案
     *
     * @param userInput 用户输入的自然语言
     * @return Agent 最终响应文本
     */
    @GetMapping("chat")
    public String chat(@RequestParam("userInput") String userInput) {
        return agentLoopEngine.agentLoop(userInput);
    }

    /**
     * 通过 Agent 查询天气的便捷接口
     * <p>
     * 示例：“北京今天天气怎么样？”
     * Agent 会自动识别该请求需要调用 getWeather 工具，
     * 拿到工具返回的 JSON 数据后再整合为自然语言回复。
     *
     * @param query 用户查询语句
     * @return Agent 最终响应文本
     */
    @GetMapping("weather/agent")
    public String weatherByAgent(@RequestParam("query") String query) {
        return agentLoopEngine.agentLoop(query);
    }
}
