package com.learn.harness;

import com.learn.harness.core.AgentLoopEngine;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/test")
public class TestController {

    @Resource
    private AgentLoopEngine agentLoopEngine;

    /**
     * 通用 Agent 对话测试
     *
     * @param userInput 用户输入
     * @return Agent 响应
     */
    @GetMapping("chat")
    public String chat(@RequestParam("userInput") String userInput) {
        return agentLoopEngine.agentLoop(userInput);
    }



    /**
     * 通过 Agent 查询天气
     * <p>
     * 示例："北京今天天气怎么样？"
     * Agent 会自动识别需要调用天气工具
     *
     * @param query 用户查询
     * @return Agent 响应
     */
    @GetMapping("weather/agent")
    public String weatherByAgent(@RequestParam("query") String query) {
        return agentLoopEngine.agentLoop(query);
    }
}
