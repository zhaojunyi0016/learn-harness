package com.learn.harness.agent;

/**
 * 【第五章】技能加载（Skill Loading）
 *
 * 本章教的是一个实用的 Agent 扩展模式：按需加载技能指令。
 *
 * 问题背景：
 * Agent 可能有很多"技能"（比如写 React 代码的规范、部署到 K8s 的步骤、写测试的最佳实践等）。
 * 如果把所有技能全塞进系统提示词，会浪费上下文空间，模型也容易被干扰。
 *
 * 解决方案：两层技能模型
 * 1. 第一层（便宜）：在系统提示词中放一个技能目录（名字 + 一句话描述）
 * 2. 第二层（按需）：模型觉得某个技能有用时，调用 load_skill 加载完整内容
 *
 * 这就像"图书馆目录"：先看目录知道有什么书，需要时再借出来看。
 *
 * 技能文件格式（skills/xxx/SKILL.md）：
 * ---
 * name: react-component
 * description: React 组件编写规范
 * ---
 * 这里是技能的完整内容...
 *
 * 核心教学点：
 * - 懒加载：不用的技能不占上下文空间
 * - Frontmatter：用 YAML 风格的头部存储元数据（名字、描述）
 * - 目录扫描：启动时自动发现所有可用技能
 *
 * 运行方式：
 *   export DASHSCOPE_API_KEY="your-key"
 *   # 先在工作目录下创建 skills/xxx/SKILL.md 文件
 *   mvn compile exec:java -Dexec.mainClass="com.learn.harness.agent.S05SkillLoading"
 */

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;

public class S05SkillLoading {

    // ==================== 配置 ====================

    private static final DashScopeClient client = new DashScopeClient();

    /** 技能文件存放目录 */
    private static final Path SKILLS_DIR = Path.of(CommonTools.WORKDIR, "skills");

    // ==================== 技能注册表 ====================

    /**
     * 技能元数据：从 SKILL.md 的 frontmatter 解析出来
     */
    static class SkillInfo {
        final String name;         // 技能名称（用于 load_skill 调用）
        final String description;  // 一句话描述（放在系统提示词的目录中）
        final String body;         // 技能完整内容（按需加载时返回）

        SkillInfo(String name, String description, String body) {
            this.name = name;
            this.description = description;
            this.body = body;
        }
    }

    /**
     * 技能注册表：负责扫描、存储和查找技能
     * <p>
     * 为什么是启动时扫描？
     * 因为技能文件不会频繁变动，启动时一次性加载比每次请求时扫描更高效。
     * 如果需要热更新，可以加一个 refresh 方法。
     */
    static class SkillRegistry {
        private final Map<String, SkillInfo> skills = new LinkedHashMap<>();

        /**
         * 扫描目录，加载所有 SKILL.md 文件
         */
        SkillRegistry(Path skillsDir) {
            if (!Files.exists(skillsDir)) return;

            try (Stream<Path> stream = Files.walk(skillsDir)) {
                stream.filter(p -> p.getFileName().toString().equals("SKILL.md"))
                        .sorted()
                        .forEach(this::loadSkillFile);
            } catch (IOException e) {
                System.err.println("[技能加载] 扫描技能目录失败: " + e.getMessage());
            }
        }

        /** 解析单个 SKILL.md 文件 */
        private void loadSkillFile(Path path) {
            try {
                String text = Files.readString(path);
                Map<String, String> parsed = parseFrontmatter(text);

                // 名字默认用目录名
                String name = parsed.getOrDefault("name", path.getParent().getFileName().toString());
                String description = parsed.getOrDefault("description", "无描述");
                String body = parsed.getOrDefault("_body", "").trim();

                skills.put(name, new SkillInfo(name, description, body));
            } catch (IOException e) {
                System.err.println("[技能加载] 加载失败: " + path + " - " + e.getMessage());
            }
        }

        /**
         * 生成技能目录（放在系统提示词中）
         * <p>
         * 格式简单明了：一行一个技能，名字 + 描述
         */
        String buildCatalog() {
            if (skills.isEmpty()) return "（暂无可用技能）";
            StringBuilder sb = new StringBuilder();
            for (SkillInfo skill : skills.values()) {
                sb.append("- ").append(skill.name).append(": ").append(skill.description).append("\n");
            }
            return sb.toString().trim();
        }

        /**
         * 加载技能完整内容（模型调用 load_skill 时触发）
         *
         * @param name 技能名称
         * @return 技能完整文本（用 XML 标签包裹，方便模型识别边界）
         */
        String loadFull(String name) {
            SkillInfo skill = skills.get(name);
            if (skill == null) {
                return "错误: 未知技能 '" + name + "'。可用技能: " + String.join(", ", skills.keySet());
            }
            // 用 XML 标签包裹，让模型清楚知道技能内容的起止
            return "<skill name=\"" + skill.name + "\">\n" + skill.body + "\n</skill>";
        }

        /**
         * 解析 Frontmatter（YAML 风格的文件头部）
         * <p>
         * 格式：
         * ---
         * key: value
         * ---
         * 正文内容...
         */
        private static Map<String, String> parseFrontmatter(String text) {
            Map<String, String> result = new HashMap<>();
            // 匹配 ---\n...\n---\n 格式
            Matcher matcher = Pattern.compile("^---\\n(.*?)\\n---\\n(.*)", Pattern.DOTALL).matcher(text);
            if (!matcher.matches()) {
                // 没有 frontmatter，整个文件作为 body
                result.put("_body", text);
                return result;
            }
            // 解析 key: value 行
            for (String line : matcher.group(1).split("\\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    result.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
                }
            }
            result.put("_body", matcher.group(2));
            return result;
        }
    }

    // 启动时扫描技能目录
    private static final SkillRegistry registry = new SkillRegistry(SKILLS_DIR);

    /** 系统提示词：包含技能目录 */
    private static final String SYSTEM_PROMPT =
            "你是一个编程助手，工作目录是 " + CommonTools.WORKDIR + "。\n" +
            "当任务需要专业知识时，使用 load_skill 加载对应技能的完整指引。\n\n" +
            "可用技能:\n" + registry.buildCatalog();

    // ==================== 工具定义 ====================

    private static List<Map<String, Object>> buildTools() {
        List<Map<String, Object>> tools = new ArrayList<>(CommonTools.allBasicToolDefs());
        tools.add(DashScopeClient.toolDefinition("load_skill",
                "加载指定技能的完整内容到当前上下文（按需使用，不要一次加载所有）",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "技能名称（从上面的目录中选择）")
                        ),
                        "required", List.of("name")
                )));
        return tools;
    }

    private static final List<Map<String, Object>> TOOLS = buildTools();

    // ==================== 工具分发 ====================

    private static String dispatchTool(String name, JsonObject arguments) {
        if ("load_skill".equals(name)) {
            return registry.loadFull(arguments.get("name").getAsString());
        }
        return CommonTools.dispatch(name, arguments);
    }

    // ==================== Agent 循环 ====================

    private static void agentLoop(List<Map<String, Object>> messages) {
        int maxTurns = 25;
        for (int turn = 0; turn < maxTurns; turn++) {
            JsonObject response = client.createMessage(SYSTEM_PROMPT, messages, TOOLS, 4096);
            messages.add(DashScopeClient.assistantMessageToMap(response));

            if (!DashScopeClient.hasToolCalls(response)) {
                return;
            }

            JsonArray toolCalls = DashScopeClient.getToolCalls(response);
            for (int i = 0; i < toolCalls.size(); i++) {
                JsonObject toolCall = toolCalls.get(i).getAsJsonObject();
                String toolName = DashScopeClient.getToolName(toolCall);
                JsonObject arguments = DashScopeClient.getToolArguments(toolCall);
                String toolCallId = DashScopeClient.getToolCallId(toolCall);

                String result;
                try {
                    result = dispatchTool(toolName, arguments);
                } catch (Exception e) {
                    result = "错误: " + e.getMessage();
                }

                // load_skill 的输出可能很长，只打印前 100 字符
                int printLen = "load_skill".equals(toolName) ? 100 : 200;
                System.out.println("\033[33m[" + toolName + "] " +
                        result.substring(0, Math.min(printLen, result.length())) + "\033[0m");
                messages.add(DashScopeClient.toolResultMessage(toolCallId, result));
            }
        }
        System.out.println("[警告] 达到最大轮次限制");
    }

    // ==================== REPL ====================

    public static void main(String[] args) throws Exception {
        List<Map<String, Object>> history = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        System.out.println("=== S05 Skill Loading 演示 ===");
        System.out.println("技能目录: " + SKILLS_DIR);
        System.out.println("已加载技能: " + registry.buildCatalog());
        System.out.println("\n提示: 在工作目录下创建 skills/<名字>/SKILL.md 文件来添加技能");
        System.out.println("输入 q 退出\n");

        while (true) {
            System.out.print("\033[36m[S05] >>> \033[0m");
            String input = reader.readLine();
            if (input == null || input.isBlank() ||
                    "q".equalsIgnoreCase(input.trim()) || "exit".equalsIgnoreCase(input.trim())) {
                break;
            }

            history.add(DashScopeClient.userMessage(input));
            agentLoop(history);
            System.out.println();
        }
    }
}
