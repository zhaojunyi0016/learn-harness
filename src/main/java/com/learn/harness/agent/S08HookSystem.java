package com.learn.harness.agent;

/**
 * 【第八章】钩子系统（Hook System）
 *
 * 本章教的是一个经典的软件设计模式：扩展点（Extension Point）。
 *
 * 问题背景：
 * Agent 循环的核心逻辑不应该频繁修改，但我们需要在特定时机做额外的事情：
 * - 工具执行前：日志记录、安全审计
 * - 工具执行后：性能统计、输出过滤
 * - 会话开始时：初始化检查、环境验证
 *
 * 解决方案：钩子（Hook）
 * 定义几个"时机"，在这些时机上注册回调。
 * 循环本身不变，额外行为通过钩子注入。
 *
 * 这就像"插座"：循环是墙，钩子是插座，你可以往插座上插各种电器。
 *
 * 钩子配置文件（.hooks.json）格式：
 * {
 *   "hooks": {
 *     "PreToolUse": [{"command": "echo pre", "matcher": "*"}],
 *     "PostToolUse": [{"command": "echo post", "matcher": "bash"}]
 *   }
 * }
 *
 * 钩子命令的退出码约定：
 * - 0: 继续执行
 * - 1: 阻止工具执行
 * - 2: 注入额外消息（通过 stderr 输出）
 *
 * 运行方式：
 *   export DASHSCOPE_API_KEY="your-key"
 *   mvn compile exec:java -Dexec.mainClass="com.learn.harness.agent.S08HookSystem"
 */

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class S08HookSystem {

    // ==================== 配置 ====================

    private static final DashScopeClient client = new DashScopeClient();

    private static final String SYSTEM_PROMPT =
            "你是一个编程助手，工作目录是 " + CommonTools.WORKDIR + "。使用工具来完成任务。";

    /** 钩子命令超时时间（秒） */
    private static final int HOOK_TIMEOUT = 30;

    // ==================== 钩子事件定义 ====================

    /**
     * 钩子触发时机（枚举比字符串更安全）
     */
    enum HookEvent {
        SESSION_START,   // 会话开始时
        PRE_TOOL_USE,    // 工具执行前
        POST_TOOL_USE    // 工具执行后
    }

    /**
     * 单个钩子的定义
     */
    static class HookDef {
        final String command;  // 要执行的 shell 命令
        final String matcher;  // 工具名匹配（"*" 表示匹配所有）

        HookDef(String command, String matcher) {
            this.command = command;
            this.matcher = matcher;
        }
    }

    /**
     * 钩子执行结果
     */
    static class HookResult {
        boolean blocked = false;       // 是否阻止工具执行
        String blockReason = "";       // 阻止原因
        List<String> messages = new ArrayList<>(); // 注入的额外消息
    }

    // ==================== 钩子管理器 ====================

    /**
     * 钩子管理器：加载配置 + 执行钩子
     */
    static class HookManager {
        private final Map<HookEvent, List<HookDef>> hooks = new EnumMap<>(HookEvent.class);

        HookManager(Path configPath) {
            // 初始化所有事件的空列表
            for (HookEvent event : HookEvent.values()) {
                hooks.put(event, new ArrayList<>());
            }

            // 从配置文件加载钩子
            if (Files.exists(configPath)) {
                loadConfig(configPath);
            } else {
                System.out.println("[钩子] 未找到配置文件 " + configPath + "（使用空配置）");
            }
        }

        private void loadConfig(Path configPath) {
            try {
                JsonObject config = JsonParser.parseString(Files.readString(configPath)).getAsJsonObject();
                JsonObject hooksObj = config.has("hooks") ? config.getAsJsonObject("hooks") : new JsonObject();

                loadEvent(hooksObj, "PreToolUse", HookEvent.PRE_TOOL_USE);
                loadEvent(hooksObj, "PostToolUse", HookEvent.POST_TOOL_USE);
                loadEvent(hooksObj, "SessionStart", HookEvent.SESSION_START);

                int total = hooks.values().stream().mapToInt(List::size).sum();
                System.out.println("[钩子] 已加载 " + total + " 个钩子");
            } catch (Exception e) {
                System.out.println("[钩子] 配置加载失败: " + e.getMessage());
            }
        }

        private void loadEvent(JsonObject hooksObj, String key, HookEvent event) {
            if (!hooksObj.has(key)) return;
            for (JsonElement el : hooksObj.getAsJsonArray(key)) {
                JsonObject h = el.getAsJsonObject();
                String command = h.has("command") ? h.get("command").getAsString() : "";
                String matcher = h.has("matcher") ? h.get("matcher").getAsString() : "*";
                if (!command.isEmpty()) {
                    hooks.get(event).add(new HookDef(command, matcher));
                }
            }
        }

        /**
         * 执行指定事件的所有钩子
         *
         * @param event    触发事件
         * @param toolName 工具名（用于 matcher 过滤）
         * @return 执行结果
         */
        HookResult runHooks(HookEvent event, String toolName) {
            HookResult result = new HookResult();
            for (HookDef hook : hooks.get(event)) {
                // 匹配检查：如果 matcher 不是 "*"，必须和工具名一致
                if (!"*".equals(hook.matcher) && !hook.matcher.equals(toolName)) {
                    continue;
                }
                executeHook(hook, event, toolName, result);
            }
            return result;
        }

        private void executeHook(HookDef hook, HookEvent event, String toolName, HookResult result) {
            try {
                ProcessBuilder pb = new ProcessBuilder("sh", "-c", hook.command);
                pb.directory(new File(CommonTools.WORKDIR));
                // 传递上下文信息给钩子脚本
                pb.environment().put("HOOK_EVENT", event.name());
                pb.environment().put("HOOK_TOOL_NAME", toolName);
                pb.redirectErrorStream(false); // 分离 stdout 和 stderr

                Process p = pb.start();
                boolean finished = p.waitFor(HOOK_TIMEOUT, TimeUnit.SECONDS);
                if (!finished) {
                    p.destroyForcibly();
                    return;
                }

                int exitCode = p.exitValue();
                // 退出码约定：0=继续，1=阻止，2=注入消息
                if (exitCode == 1) {
                    result.blocked = true;
                    result.blockReason = "被 " + event.name() + " 钩子阻止";
                } else if (exitCode == 2) {
                    String msg = new String(p.getErrorStream().readAllBytes()).trim();
                    if (!msg.isEmpty()) {
                        result.messages.add(msg);
                    }
                }
            } catch (Exception e) {
                System.out.println("  [钩子错误] " + event.name() + ": " + e.getMessage());
            }
        }
    }

    // ==================== Agent 循环（带钩子） ====================

    private static void agentLoop(List<Map<String, Object>> messages, HookManager hooks) {
        int maxTurns = 25;
        for (int turn = 0; turn < maxTurns; turn++) {
            JsonObject response = client.createMessage(SYSTEM_PROMPT, messages, CommonTools.allBasicToolDefs(), 4096);
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

                // PreToolUse 钩子
                HookResult preResult = hooks.runHooks(HookEvent.PRE_TOOL_USE, toolName);
                String result;
                if (preResult.blocked) {
                    result = "被钩子阻止: " + preResult.blockReason;
                    System.out.println("  \033[31m[阻止] " + toolName + " - " + preResult.blockReason + "\033[0m");
                } else {
                    // 执行工具
                    try {
                        result = CommonTools.dispatch(toolName, arguments);
                    } catch (Exception e) {
                        result = "错误: " + e.getMessage();
                    }
                    System.out.println("\033[33m[" + toolName + "] " +
                            result.substring(0, Math.min(200, result.length())) + "\033[0m");

                    // PostToolUse 钩子
                    hooks.runHooks(HookEvent.POST_TOOL_USE, toolName);
                }

                messages.add(DashScopeClient.toolResultMessage(toolCallId, result));
            }
        }
        System.out.println("[警告] 达到最大轮次限制");
    }

    // ==================== REPL ====================

    public static void main(String[] args) throws Exception {
        Path hookConfig = Path.of(CommonTools.WORKDIR, ".hooks.json");
        HookManager hooks = new HookManager(hookConfig);

        // 触发 SessionStart 钩子
        hooks.runHooks(HookEvent.SESSION_START, "");

        List<Map<String, Object>> history = new ArrayList<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        System.out.println("=== S08 Hook System 演示 ===");
        System.out.println("钩子配置文件: " + hookConfig);
        System.out.println("提示: 创建 .hooks.json 来注册钩子");
        System.out.println("输入 q 退出\n");

        while (true) {
            System.out.print("\033[36m[S08] >>> \033[0m");
            String input = reader.readLine();
            if (input == null || input.isBlank() ||
                    "q".equalsIgnoreCase(input.trim()) || "exit".equalsIgnoreCase(input.trim())) {
                break;
            }

            history.add(DashScopeClient.userMessage(input));
            agentLoop(history, hooks);
            System.out.println();
        }
    }
}
