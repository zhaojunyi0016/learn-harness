package com.learn.harness.agent;

/**
 * 【第九章】记忆系统（Memory System）
 *
 * 本章教的是让 Agent 拥有跨会话记忆的能力。
 *
 * 问题背景：
 * 每次对话结束后，Agent 的上下文就清空了。但有些信息是"长期有用"的：
 * - 用户偏好（"我喜欢 tab 缩进"、"不要用 var"）
 * - 项目事实（"这个项目用的是 Gradle 7"、"部署在阿里云上"）
 * - 之前的反馈（"上次你犯的错：不要随便删 pom.xml"）
 *
 * 解决方案：文件系统记忆
 * - 每条记忆是一个 Markdown 文件（带 frontmatter 元数据）
 * - 存在 .memory/ 目录下
 * - 启动时加载所有记忆到系统提示词
 * - 运行时模型可以通过 save_memory 工具创建新记忆
 *
 * 为什么用文件而不是数据库？
 * 因为 Markdown 文件人类可读、Git 可追踪、无需额外依赖。
 *
 * 核心教学点：
 * - 记忆是"跨会话"的信息存储
 * - 不是所有信息都值得记忆（代码里能找到的不需要记）
 * - 记忆有分类（user/project/feedback/reference）
 * - 启动时加载 → 运行时创建 → 下次启动时自动可用
 *
 * 运行方式：
 *   export DASHSCOPE_API_KEY="your-key"
 *   mvn compile exec:java -Dexec.mainClass="com.learn.harness.agent.S09MemorySystem"
 */

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class S09MemorySystem {

    // ==================== 配置 ====================

    private static final DashScopeClient client = new DashScopeClient();

    /** 记忆存储目录 */
    private static final Path MEMORY_DIR = Path.of(CommonTools.WORKDIR, ".memory");

    /** 记忆索引文件 */
    private static final Path MEMORY_INDEX = MEMORY_DIR.resolve("MEMORY.md");

    /** 允许的记忆类型 */
    private static final List<String> MEMORY_TYPES = List.of("user", "feedback", "project", "reference");

    // ==================== 记忆管理器 ====================

    /**
     * 单条记忆
     */
    static class Memory {
        String name;        // 记忆名称（唯一标识）
        String description; // 一句话描述
        String type;        // 类型（user/feedback/project/reference）
        String content;     // 详细内容

        Memory(String name, String description, String type, String content) {
            this.name = name;
            this.description = description;
            this.type = type;
            this.content = content;
        }
    }

    /**
     * 记忆管理器：负责加载、保存和检索记忆
     */
    static class MemoryManager {
        private final Path memoryDir;
        private final Map<String, Memory> memories = new LinkedHashMap<>();

        MemoryManager(Path memoryDir) {
            this.memoryDir = memoryDir;
        }

        /**
         * 从磁盘加载所有记忆
         */
        void loadAll() {
            memories.clear();
            if (!Files.isDirectory(memoryDir)) return;

            try (var stream = Files.list(memoryDir)) {
                stream.filter(f -> f.toString().endsWith(".md"))
                        .filter(f -> !f.getFileName().toString().equals("MEMORY.md"))
                        .sorted()
                        .forEach(this::loadFile);
            } catch (IOException e) {
                System.out.println("[记忆] 加载失败: " + e.getMessage());
            }

            if (!memories.isEmpty()) {
                System.out.println("[记忆] 已加载 " + memories.size() + " 条记忆");
            }
        }

        private void loadFile(Path file) {
            try {
                String text = Files.readString(file);
                Map<String, String> parsed = parseFrontmatter(text);
                if (parsed == null) return;

                String name = parsed.getOrDefault("name", file.getFileName().toString().replace(".md", ""));
                String description = parsed.getOrDefault("description", "");
                String type = parsed.getOrDefault("type", "project");
                String content = parsed.getOrDefault("_body", "");

                memories.put(name, new Memory(name, description, type, content));
            } catch (IOException ignored) {}
        }

        /**
         * 生成记忆段落（插入系统提示词）
         * <p>
         * 按类型分组展示，方便模型快速了解已有记忆
         */
        String buildMemoryPrompt() {
            if (memories.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("# 持久化记忆（跨会话保留）\n\n");
            for (String type : MEMORY_TYPES) {
                List<Memory> typed = memories.values().stream()
                        .filter(m -> type.equals(m.type))
                        .toList();
                if (typed.isEmpty()) continue;
                sb.append("## [").append(type).append("]\n");
                for (Memory mem : typed) {
                    sb.append("### ").append(mem.name).append(": ").append(mem.description).append("\n");
                    if (!mem.content.isEmpty()) {
                        sb.append(mem.content).append("\n");
                    }
                    sb.append("\n");
                }
            }
            return sb.toString();
        }

        /**
         * 保存新记忆（模型调用 save_memory 工具时触发）
         */
        String save(String name, String description, String type, String content) {
            if (!MEMORY_TYPES.contains(type)) {
                return "错误: type 必须是 " + MEMORY_TYPES + " 之一";
            }
            // 文件名安全处理
            String safeName = name.toLowerCase().replaceAll("[^a-zA-Z0-9_\\-]", "_");
            if (safeName.isEmpty()) return "错误: 无效的记忆名称";

            try {
                Files.createDirectories(memoryDir);
                String fileContent = "---\nname: " + name + "\ndescription: " + description +
                        "\ntype: " + type + "\n---\n" + content + "\n";
                Path filePath = memoryDir.resolve(safeName + ".md");
                Files.writeString(filePath, fileContent);

                memories.put(name, new Memory(name, description, type, content));
                rebuildIndex();

                return "已保存记忆 '" + name + "' [" + type + "]";
            } catch (IOException e) {
                return "错误: " + e.getMessage();
            }
        }

        /**
         * 重建索引文件（方便人类查看）
         */
        private void rebuildIndex() {
            List<String> lines = new ArrayList<>();
            lines.add("# 记忆索引\n");
            for (Memory mem : memories.values()) {
                lines.add("- [" + mem.type + "] " + mem.name + ": " + mem.description);
            }
            try {
                Files.createDirectories(memoryDir);
                Files.writeString(MEMORY_INDEX, String.join("\n", lines) + "\n");
            } catch (IOException ignored) {}
        }

        /**
         * 解析 Markdown 文件的 Frontmatter
         */
        private Map<String, String> parseFrontmatter(String text) {
            Matcher m = Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)", Pattern.DOTALL).matcher(text);
            if (!m.matches()) return null;
            Map<String, String> result = new HashMap<>();
            for (String line : m.group(1).split("\n")) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    result.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
                }
            }
            result.put("_body", m.group(2).trim());
            return result;
        }
    }

    // 全局记忆管理器
    private static final MemoryManager memoryMgr = new MemoryManager(MEMORY_DIR);

    // ==================== 系统提示词（含记忆） ====================

    /** 记忆使用指南（告诉模型什么时候该存记忆） */
    private static final String MEMORY_GUIDANCE =
            "## 何时保存记忆\n" +
            "- 用户说了偏好 → type: user\n" +
            "- 用户纠正了你 → type: feedback\n" +
            "- 发现了项目中不明显的事实 → type: project\n" +
            "- 有用的外部链接/参考 → type: reference\n" +
            "## 何时不该保存\n" +
            "- 代码中能直接看到的信息\n" +
            "- 临时任务状态\n" +
            "- 密码或密钥\n";

    private static String buildSystemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个编程助手，工作目录是 ").append(CommonTools.WORKDIR).append("。\n\n");
        String memPrompt = memoryMgr.buildMemoryPrompt();
        if (!memPrompt.isEmpty()) {
            sb.append(memPrompt).append("\n");
        }
        sb.append(MEMORY_GUIDANCE);
        return sb.toString();
    }

    // ==================== 工具定义 ====================

    private static List<Map<String, Object>> buildTools() {
        List<Map<String, Object>> tools = new ArrayList<>(CommonTools.allBasicToolDefs());
        tools.add(DashScopeClient.toolDefinition("save_memory",
                "保存一条持久化记忆（跨会话保留，下次启动时自动加载）",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "记忆名称（简短标识）"),
                                "description", Map.of("type", "string", "description", "一句话描述这条记忆"),
                                "type", Map.of("type", "string", "description", "类型: user/feedback/project/reference"),
                                "content", Map.of("type", "string", "description", "记忆详细内容")
                        ),
                        "required", List.of("name", "description", "type", "content")
                )));
        return tools;
    }

    private static final List<Map<String, Object>> TOOLS = buildTools();

    // ==================== Agent 循环 ====================

    private static void agentLoop(List<Map<String, Object>> messages) {
        int maxTurns = 25;
        for (int turn = 0; turn < maxTurns; turn++) {
            // 每轮都重新构建系统提示词（因为可能有新记忆加入）
            String systemPrompt = buildSystemPrompt();
            JsonObject response = client.createMessage(systemPrompt, messages, TOOLS, 4096);
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
                    if ("save_memory".equals(toolName)) {
                        result = memoryMgr.save(
                                arguments.get("name").getAsString(),
                                arguments.get("description").getAsString(),
                                arguments.get("type").getAsString(),
                                arguments.get("content").getAsString()
                        );
                    } else {
                        result = CommonTools.dispatch(toolName, arguments);
                    }
                } catch (Exception e) {
                    result = "错误: " + e.getMessage();
                }

                System.out.println("\033[33m[" + toolName + "] " +
                        result.substring(0, Math.min(200, result.length())) + "\033[0m");
                messages.add(DashScopeClient.toolResultMessage(toolCallId, result));
            }
        }
        System.out.println("[警告] 达到最大轮次限制");
    }

    // ==================== REPL ====================

    public static void main(String[] args) throws Exception {
        // 启动时加载记忆
        memoryMgr.loadAll();

        List<Map<String, Object>> history = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        System.out.println("=== S09 Memory System 演示 ===");
        System.out.println("记忆目录: " + MEMORY_DIR);
        System.out.println("已有记忆: " + memoryMgr.memories.size() + " 条");
        System.out.println("命令: /memories 查看所有记忆");
        System.out.println("输入 q 退出\n");

        while (true) {
            System.out.print("\033[36m[S09] >>> \033[0m");
            String input = reader.readLine();
            if (input == null || input.isBlank() ||
                    "q".equalsIgnoreCase(input.trim()) || "exit".equalsIgnoreCase(input.trim())) {
                break;
            }

            // 特殊命令：查看记忆列表
            if ("/memories".equals(input.trim())) {
                if (memoryMgr.memories.isEmpty()) {
                    System.out.println("  （暂无记忆）");
                } else {
                    memoryMgr.memories.values().forEach(mem ->
                            System.out.println("  [" + mem.type + "] " + mem.name + ": " + mem.description));
                }
                System.out.println();
                continue;
            }

            history.add(DashScopeClient.userMessage(input));
            agentLoop(history);
            System.out.println();
        }
    }
}
