package com.learn.harness.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

/**
 * 天气查询工具
 * <p>
 * 提供实时天气查询功能，返回指定城市的温度、湿度、风力等信息
 */
@Service
public class WeatherTool {

    private static final Logger logger = LoggerFactory.getLogger(WeatherTool.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 查询天气
     *
     * @param city 城市名称（如：北京、上海、杭州）
     * @return 天气信息 JSON 字符串
     */
    @Tool(name = "getWeather", description = "获取指定城市的实时天气情况")
    public String queryWeather(String city) {
        logger.info("查询城市天气: {}", city);

        try {
            // 模拟 天气数据（实际项目中可调用第三方天气 API）
            String weatherData = getMockWeatherData(city);
            return weatherData;
        } catch (Exception e) {
            logger.error("查询天气失败", e);
            return "{\"error\": \"查询天气失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 获取模拟天气数据
     * <p>
     * 实际项目中应替换为真实的天气 API 调用
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
