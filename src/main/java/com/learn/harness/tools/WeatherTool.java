package com.learn.harness.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

/**
 * 天气查询工具（Agent 可调用的 Tool）
 * <p>
 * 作用：
 * 1. 通过 {@link Tool} 注解暴露方法，让 Spring AI 自动识别为可调用工具
 * 2. 当用户提问与天气相关时，模型会自主决策是否调用本类方法
 * 3. 当前实现返回模拟数据，后续可替换为真实第三方天气 API 调用
 */
@Service
public class WeatherTool {

    private static final Logger logger = LoggerFactory.getLogger(WeatherTool.class);

    /** 用于把 Java 对象序列化为 JSON 字符串 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 查询指定城市的实时天气
     * <p>
     * 该方法被 @Tool 注解标记后，会被 ToolCallbacks.from(weatherTool) 自动扫描出来，
     * 注册到 AgentLoopEngine 的工具列表中，模型可以通过 function calling 调用本方法。
     *
     * @param city 城市名称，例如：北京、上海、杭州
     * @return 天气信息的 JSON 字符串（包含温度、湿度、风力等）
     */
    @Tool(name = "getWeather", description = "获取指定城市的实时天气情况")
    public String queryWeather(String city) {
        logger.info("查询城市天气: {}", city);

        try {
            // 模拟天气数据（实际项目中可在此调用第三方天气 API）
            return getMockWeatherData(city);
        } catch (Exception e) {
            logger.error("查询天气失败", e);
            return "{\"error\": \"查询天气失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 生成模拟的天气 JSON 数据
     * <p>
     * 真实项目中应替换为实际的天气 API 调用（如和风天气、心知天气等）。
     *
     * @param city 城市名称
     * @return 模拟天气数据的 JSON 字符串
     */
    private String getMockWeatherData(String city) {
        try {
            ObjectNode weather = objectMapper.createObjectNode();
            weather.put("city", city);
            weather.put("temperature", "18-25°C");
            weather.put("humidity", "65%");
            weather.put("wind", "东南风 3-4级");
            weather.put("aqi", "良");
            weather.put("description", "今天天气晴朗，适合出行");
            weather.put("updateTime", java.time.LocalDateTime.now().toString());

            return objectMapper.writeValueAsString(weather);
        } catch (Exception e) {
            return "{\"error\": \"生成天气数据失败\"}";
        }
    }
}
