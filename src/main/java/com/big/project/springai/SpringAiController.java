package com.big.project.springai;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;


@RestController
public class SpringAiController {

    @Resource
    private DashScopeChatModel dashScopeChatModel;

    @RequestMapping(path = "/ai")
    public String helloAi(String q) {
       return dashScopeChatModel.call("你是谁");
    }
}