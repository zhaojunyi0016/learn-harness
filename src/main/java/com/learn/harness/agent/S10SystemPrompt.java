package com.learn.harness.agent;

/**
 * 【第十章】系统提示词构建（System Prompt Construction）
 *
 * 本章教的核心观点：系统提示词不是一个字符串，而是一条流水线。
 *
 * 问题背景：
 * 一个完整的 Agent 的系统提示词可能包含很多部分：
 * - 核心角色指令（你是谁、能做什么）
 * - 可用工具列表
 * - 技能目录
 * - 持久化记忆
 * - 项目规则（CLAUDE.md / AGENTS.md）
 * - 动态上下文（日期、路径、环境）
 *
 * 如果把这些硬编码成一个大字符串，维护起来会非常痛苦。
 *
 * 解决方案：流水线式构建
 * 每个部分是一个独立的方法（section），最后按顺序拼接。
 * 这样每个 section 可以独立修改、独立测试、独立开关。
 *
 * 核心教学点：
 * - 提示词有"静态部分"和"动态部分"的分界线
 * - 每个 section 独立生成，空则跳过
 * - 可以用 /prompt 命令查看最终拼接结果（方便调试）
 *
 * 运行方式：
 *   export DASHSCOPE_API_KEY="your-key"
 *   mvn compile exec:java -Dexec.mainClass="com.learn.harness.agent.S10SystemPrompt"
 */

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

public class S10SystemPrompt {

    // ==================== 配置 ====================

    private static final DashScopeClient client = new DashScopeClient();

    /** 动态/静态分界线标记 */
    private static final String DYNAMIC_BOUNDARY = "=== 以下为动态上下文 ===";

    // ==================== 系统提示词构建器 ====================

    /**
     * 系统提示词构建器：流水线式拼接各部分
     * <p>
     * 为什么做成类？
     * 因为各 section 之间可能有依赖（比如技能目录要扫描文件系统），
     * 用类封装可以共享配置（workdir、skillsDir 等），比纯函数更方便。
     */
    static class SystemPromptBuilder {
        private final Path workdir;
        private final Path skillsDir;
        private final Path memoryDir;

        SystemPromptBuilder(Path workdir) {
            this.workdir = workdir;
            this.skillsDir = workdir.resolve("skills");
            this.memoryDir = workdir.resolve(".memory");
        }

        /**
         * Section 1：核心指令
         * <p>
         * 最基本的角色定义——告诉模型它是什么、在哪工作、基本原则是什么
         */
        String buildCore() {
            return "你是一个编程助手，工作目录是 " + workdir + "。\n" +
                    "使用工具来探索、阅读和修改代码。\n" +
                    "先验证再假设，优先读文件而不是猜测。";
        }

        /**
         * Section 2：工具列表
         * <p>
         * 把可用工具列成清单，让模型知道自己有什么能力
         */
        String buildToolListing() {
            List<Map<String, Object>> tools = CommonTools.allBasicToolDefs();
            if (tools.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("# 可用工具\n");
            for (Map<String, Object> tool : tools) {
                @SuppressWarnings("unchecked")
                Map<String, Object> function = (Map<String, Object>) tool.get("function");
                sb.append("- ").append(function.get("name"))
                        .append(": ").append(function.get("description")).append("\n");
            }
            return sb.toString();
        }

        /**
         * Section 3：技能目录
         */
        String buildSkillListing() {
            if (!Files.isDirectory(skillsDir)) return "";
            List<String> skills = new ArrayList<>();
            try (var dirs = Files.list(skillsDir)) {
                dirs.filter(Files::isDirectory).sorted().forEach(dir -> {
                    Path skillMd = dir.resolve("SKILL.md");
                    if (!Files.exists(skillMd)) return;
                    try {
                        String text = Files.readString(skillMd);
                        Map<String, String> meta = parseFrontmatter(text);
                        String name = meta.getOrDefault("name", dir.getFileName().toString());
                        String desc = meta.getOrDefault("description", "");
                        skills.add("- " + name + ": " + desc);
                    } catch (IOException ignored) {}
                });
            } catch (IOException ignored) {}
            if (skills.isEmpty()) return "";
            return "# 可用技能\n" + String.join("\n", skills);
        }

        /**
         * Section 4：记忆内容
         */
        String buildMemorySection() {
            if (!Files.isDirectory(memoryDir)) return "";
            List<String> memories = new ArrayList<>();
            try (var stream = Files.list(memoryDir)) {
                stream.filter(f -> f.toString().endsWith(".md"))
                        .filter(f -> !f.getFileName().toString().equals("MEMORY.md"))
                        .sorted()
                        .forEach(f -> {
                            try {
                                String text = Files.readString(f);
                                Map<String, String> meta = parseFrontmatter(text);
                                if (meta.isEmpty()) return;
                                String name = meta.getOrDefault("name", f.getFileName().toString().replace(".md", ""));
                                String type = meta.getOrDefault("type", "project");
                                String desc = meta.getOrDefault("description", "");
                                String body = meta.getOrDefault("_body", "").trim();
                                memories.add("[" + type + "] " + name + ": " + desc +
                                        (body.isEmpty() ? "" : "\n" + body));
                            } catch (IOException ignored) {}
                        });
            } catch (IOException ignored) {}
            if (memories.isEmpty()) return "";
            return "# 持久化记忆\n\n" + String.join("\n\n", memories);
        }

        /**
         * Section 5：项目规则文件
         * <p>
         * 读取项目根目录的 CLAUDE.md 或 AGENTS.md（如果存在）
         */
        String buildProjectRules() {
            List<String> parts = new ArrayList<>();
            // 检查 CLAUDE.md
            for (String filename : List.of("CLAUDE.md", "AGENTS.md")) {
                Path rulePath = workdir.resolve(filename);
                if (Files.exists(rulePath)) {
                    try {
                        parts.add("## " + filename + "\n" + Files.readString(rulePath).trim());
                    } catch (IOException ignored) {}
                }
            }
            if (parts.isEmpty()) return "";
            return "# 项目规则\n\n" + String.join("\n\n", parts);
        }

        /**
         * Section 6：动态上下文
         * <p>
         * 每次构建都会变化的信息：日期、环境等
         */
        String buildDynamicContext() {
            return "# 动态上下文\n" +
                    "当前日期: " + LocalDate.now() + "\n" +
                    "工作目录: " + workdir + "\n" +
                    "操作系统: " + System.getProperty("os.name");
        }

        /**
         * 组装完整的系统提示词
         * <p>
         * 按顺序拼接所有非空 section，用分隔线隔开
         */
        String build() {
            List<String> sections = new ArrayList<>();

            addIfNotEmpty(sections, buildCore());
            addIfNotEmpty(sections, buildToolListing());
            addIfNotEmpty(sections, buildSkillListing());
            addIfNotEmpty(sections, buildMemorySection());
            addIfNotEmpty(sections, buildProjectRules());
            sections.add(DYNAMIC_BOUNDARY);
            addIfNotEmpty(sections, buildDynamicContext());

            return String.join("\n\n", sections);
        }

        private void addIfNotEmpty(List<String> sections, String content) {
            if (content != null && !content.isEmpty()) {
                sections.add(content);
            }
        }

        /**
         * 解析 Markdown Frontmatter
         */
        private Map<String, String> parseFrontmatter(String text) {
            Matcher m = Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n(.*)", Pattern.DOTALL).matcher(text);
            if (!m.matches()) return Map.of();
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

    // 全局构建器
    private static final SystemPromptBuilder promptBuilder =
            new SystemPromptBuilder(Path.of(CommonTools.WORKDIR));

    // ==================== Agent 循环 ====================

    private static void agentLoop(List<Map<String, Object>> messages) {
        int maxTurns = 25;
        for (int turn = 0; turn < maxTurns; turn++) {
            // 每轮重新构建系统提示词（动态部分可能变化）
            String systemPrompt = promptBuilder.build();
            JsonObject response = client.createMessage(systemPrompt, messages, CommonTools.allBasicToolDefs(), 4096);
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
                    result = CommonTools.dispatch(toolName, arguments);
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
        // 启动时展示系统提示词信息
        String fullPrompt = promptBuilder.build();
        long sectionCount = fullPrompt.lines().filter(l -> l.startsWith("# ")).count();
        System.out.println("=== S10 System Prompt 演示 ===");
        System.out.printf("[系统提示词] %d 字符，%d 个段落%n", fullPrompt.length(), sectionCount);
        System.out.println("命令: /prompt 查看完整提示词 | /sections 查看段落标题");
        System.out.println("输入 q 退出\n");

        List<Map<String, Object>> history = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        while (true) {
            System.out.print("\033[36m[S10] >>> \033[0m");
            String input = reader.readLine();
            if (input == null || input.isBlank() ||
                    "q".equalsIgnoreCase(input.trim()) || "exit".equalsIgnoreCase(input.trim())) {
                break;
            }

            // 特殊命令
            if ("/prompt".equals(input.trim())) {
                System.out.println("--- 系统提示词开始 ---");
                System.out.println(promptBuilder.build());
                System.out.println("--- 系统提示词结束 ---\n");
                continue;
            }
            if ("/sections".equals(input.trim())) {
                for (String line : promptBuilder.build().split("\n")) {
                    if (line.startsWith("# ") || line.equals(DYNAMIC_BOUNDARY)) {
                        System.out.println("  " + line);
                    }
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
