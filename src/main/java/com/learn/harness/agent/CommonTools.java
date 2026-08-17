package com.learn.harness.agent;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公共工具方法（所有教程文件共用）
 * <p>
 * 为什么把这些方法提取到这里？
 * 因为 S01~S19 每个文件都有一份一模一样的 bash/read/write/edit 实现，
 * 重复代码不仅浪费空间，修改时还要改 20 遍。
 * 提到公共类后，各教程文件直接调用 CommonTools.runBash(...)，干净利落。
 * <p>
 * 为什么用 static 方法而不是接口/注册表？
 * 因为这些工具的行为是固定的（读文件就是读文件），不需要运行时替换。
 * 用静态方法最简单直接，一行代码就能调用。
 * 只有在需要演示"工具动态注册/分发"的章节（如 S02），才会引入接口。
 */
public class CommonTools {

    /** 工作目录（默认为项目运行时的当前目录） */
    public static final String WORKDIR = System.getProperty("user.dir");

    /** 工具输出的最大长度（防止模型收到超长内容导致上下文溢出） */
    private static final int MAX_OUTPUT_LENGTH = 50000;

    /** 命令执行超时时间（秒） */
    private static final int BASH_TIMEOUT_SECONDS = 120;

    /**
     * 路径安全检查
     * <p>
     * 为什么需要这个？
     * 模型生成的路径可能包含 "../" 等内容试图逃逸出工作目录，
     * 这个方法确保所有文件操作都限制在工作目录内。
     *
     * @param relativePath 相对路径
     * @return 安全的绝对路径
     * @throws IllegalArgumentException 如果路径逃逸出工作目录
     */
    public static Path safePath(String relativePath) {
        Path path = Path.of(WORKDIR).resolve(relativePath).normalize();
        if (!path.startsWith(WORKDIR)) {
            throw new IllegalArgumentException("路径逃逸出工作目录: " + relativePath);
        }
        return path;
    }

    /**
     * 执行 Shell 命令
     * <p>
     * 这是 Agent 最常用的工具——让模型能够在本地环境执行命令。
     * 实现了两个安全措施：
     * 1. 危险命令黑名单检查（防止 rm -rf / 等）
     * 2. 超时控制（防止命令无限运行卡死整个 Agent）
     *
     * @param command Shell 命令字符串
     * @return 命令输出（stdout + stderr 合并）
     */
    public static String runBash(String command) {
        // 危险命令检查：模型有时会生成破坏性命令，这里做最基本的拦截
        List<String> dangerous = List.of("rm -rf /", "sudo", "shutdown", "reboot", "> /dev/");
        for (String d : dangerous) {
            if (command.contains(d)) {
                return "错误: 危险命令被拦截 - " + d;
            }
        }

        try {
            // 用 sh -c 执行，这样命令中的管道、重定向等都能正常工作
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.directory(new File(WORKDIR));
            pb.redirectErrorStream(true); // stderr 合并到 stdout，方便模型看到完整输出

            Process process = pb.start();

            // 超时控制：避免死循环命令卡住整个 Agent
            boolean finished = process.waitFor(BASH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "错误: 命令超时（" + BASH_TIMEOUT_SECONDS + "秒）";
            }

            String output = new String(process.getInputStream().readAllBytes()).trim();

            // 空输出和超长输出的处理
            if (output.isEmpty()) return "(无输出)";
            return truncate(output);
        } catch (Exception e) {
            return "错误: " + e.getMessage();
        }
    }

    /**
     * 读取文件内容
     *
     * @param path  文件相对路径
     * @param limit 最多读取行数（null 表示读取全部）
     * @return 文件内容字符串
     */
    public static String readFile(String path, Integer limit) {
        try {
            List<String> lines = Files.readAllLines(safePath(path));
            if (limit != null && limit < lines.size()) {
                lines = new ArrayList<>(lines.subList(0, limit));
                lines.add("... (还有 " + (Files.readAllLines(safePath(path)).size() - limit) + " 行)");
            }
            String result = String.join("\n", lines);
            return truncate(result);
        } catch (Exception e) {
            return "错误: " + e.getMessage();
        }
    }

    /** 读取文件全部内容（不限行数） */
    public static String readFile(String path) {
        return readFile(path, null);
    }

    /**
     * 写入文件
     * <p>
     * 会自动创建不存在的父目录。
     *
     * @param path    文件相对路径
     * @param content 要写入的内容
     * @return 操作结果描述
     */
    public static String writeFile(String path, String content) {
        try {
            Path fp = safePath(path);
            Files.createDirectories(fp.getParent());
            Files.writeString(fp, content);
            return "已写入 " + content.length() + " 字节到 " + path;
        } catch (Exception e) {
            return "错误: " + e.getMessage();
        }
    }

    /**
     * 编辑文件（精确替换）
     * <p>
     * 为什么用精确文本匹配而不是行号？
     * 因为模型看到的文件内容可能和实际行号对不上（比如之前有过编辑），
     * 用确切的文本片段匹配更可靠。
     *
     * @param path       文件相对路径
     * @param oldText    要替换的原始文本
     * @param newText    替换后的新文本
     * @return 操作结果描述
     */
    public static String editFile(String path, String oldText, String newText) {
        try {
            Path fp = safePath(path);
            String content = Files.readString(fp);
            if (!content.contains(oldText)) {
                return "错误: 在 " + path + " 中未找到要替换的文本";
            }
            // 只替换第一次出现（避免意外替换多处）
            content = content.replaceFirst(
                    Pattern.quote(oldText),
                    Matcher.quoteReplacement(newText)
            );
            Files.writeString(fp, content);
            return "已编辑 " + path;
        } catch (Exception e) {
            return "错误: " + e.getMessage();
        }
    }

    /**
     * 截断过长的文本
     * <p>
     * 为什么要截断？
     * 模型的上下文窗口有限，如果工具返回了巨长的输出（比如大文件内容），
     * 会挤掉对话历史中的有用信息。截断后保证不会超限。
     */
    private static String truncate(String text) {
        if (text.length() > MAX_OUTPUT_LENGTH) {
            return text.substring(0, MAX_OUTPUT_LENGTH) + "\n... (已截断，共 " + text.length() + " 字符)";
        }
        return text;
    }

    // ==================== 工具定义（给模型看的 schema） ====================
    // 以下方法返回 OpenAI function calling 格式的工具定义，
    // 教程文件调用这些方法构建工具列表传给模型。

    /** bash 工具定义 */
    public static Map<String, Object> bashToolDef() {
        return DashScopeClient.toolDefinition("bash",
                "在当前工作目录执行 Shell 命令",
                Map.of(
                        "type", "object",
                        "properties", Map.of("command", Map.of("type", "string", "description", "要执行的命令")),
                        "required", List.of("command")
                ));
    }

    /** read_file 工具定义 */
    public static Map<String, Object> readFileToolDef() {
        return DashScopeClient.toolDefinition("read_file",
                "读取文件内容",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "文件相对路径"),
                                "limit", Map.of("type", "integer", "description", "最多读取行数（可选）")
                        ),
                        "required", List.of("path")
                ));
    }

    /** write_file 工具定义 */
    public static Map<String, Object> writeFileToolDef() {
        return DashScopeClient.toolDefinition("write_file",
                "把内容  写入文件（会覆盖原有内容）",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "文件相对路径"),
                                "content", Map.of("type", "string", "description", "要写入的内容")
                        ),
                        "required", List.of("path", "content")
                ));
    }

    /** edit_file 工具定义 */   
    public static Map<String, Object> editFileToolDef() {
        return DashScopeClient.toolDefinition("edit_file",
                "精确 替换 文件中的一段文本",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "文件相对路径"),
                                "old_text", Map.of("type", "string", "description", "要被替换的原始文本"),
                                "new_text", Map.of("type", "string", "description", "替换后的新文本")
                        ),
                        "required", List.of("path", "old_text", "new_text")
                ));
    }

    /** 获取所有基础工具定义列表（bash + read + write + edit） */
    public static List<Map<String, Object>> allBasicToolDefs() {
        return List.of(bashToolDef(), readFileToolDef(), writeFileToolDef(), editFileToolDef());
    }

    /**
     * 根据工具名分发执行
     * <p>
     * 这是最基础的工具分发逻辑：收到模型的 tool_call，
     * 根据工具名找到对应的方法去执行。
     *
     * @param toolName  工具名称
     * @param arguments 工具参数（JSON 对象）
     * @return 工具执行结果
     */
    public static String dispatch(String toolName, com.google.gson.JsonObject arguments) {
        return switch (toolName) {
            case "bash" -> runBash(arguments.get("command").getAsString());
            case "read_file" -> readFile(
                    arguments.get("path").getAsString(),
                    arguments.has("limit") ? arguments.get("limit").getAsInt() : null
            );
            case "write_file" -> writeFile(
                    arguments.get("path").getAsString(),
                    arguments.get("content").getAsString()
            );
            case "edit_file" -> editFile(
                    arguments.get("path").getAsString(),
                    arguments.get("old_text").getAsString(),
                    arguments.get("new_text").getAsString()
            );
            default -> "未知工具: " + toolName;
        };
    }
}
