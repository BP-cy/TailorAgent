package com.changy.tailoragent.tool.memory;

import com.changy.tailoragent.tool.support.ToolInputException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 记忆工具 —— 模型读写「跨会话记忆库」的入口(三件套:读 / 写 / 删)。
 * <p>
 * 记忆按工作区隔离,目录与路径解析全在 {@link MemoryRegistry},模型只需传逻辑名(如 {@code 用户习惯.md})。
 * 二级索引结构({@code MEMORY.md} 总索引 → 板块文件 → 条目)由系统提示词约定、模型自行维护:
 * 写入/删除条目后应同步更新 {@code MEMORY.md} 与所属板块文件的条目索引。
 * <p>
 * 错误约定同其它本地工具:不抛异常,统一返回以「错误: 」开头的字符串(由 Spring AI 回喂模型)。
 * 工具调用本身经 {@code ToolAggregator → EventEmittingToolCallback} 自动落 tool_call/tool_result 并推前端。
 */
@Component
public class MemoryTool {

    private static final Logger log = LoggerFactory.getLogger(MemoryTool.class);

    private final MemoryRegistry registry;

    public MemoryTool(MemoryRegistry registry) {
        this.registry = registry;
    }

    @Tool(name = "memory_read", description =
            "读取一条记忆文件的完整内容。当系统提示中的『当前记忆索引』显示某条记忆与当前任务相关时调用。" +
            "name 为相对记忆库根的路径(如 \"用户习惯.md\"、\"用户习惯/简洁回答.md\"),省略扩展名时默认 .md。")
    public String memoryRead(
            @ToolParam(description = "要读取的记忆名称/相对路径,如 \"项目偏好.md\"") String name) {
        try {
            return registry.read(name);
        } catch (ToolInputException e) {
            return "错误: " + e.getMessage();
        } catch (RuntimeException e) {
            log.warn("读取记忆失败: {}", e.getMessage());
            return "错误: 读取记忆失败: " + e.getMessage();
        }
    }

    @Tool(name = "memory_write", description =
            "创建或全量覆盖一条记忆文件,用于长期保留用户习惯、项目偏好、参考资料、反馈纠正、功能实现等" +
            "无法从代码/项目状态直接推断的信息。name 为相对记忆库根的路径(省略扩展名时默认 .md)。" +
            "重要:写入条目后,须同步更新 MEMORY.md 总索引及所属板块文件的条目索引,保持两级结构一致。")
    public String memoryWrite(
            @ToolParam(description = "记忆名称/相对路径,如 \"用户习惯.md\"") String name,
            @ToolParam(description = "要写入的完整 Markdown 内容") String content) {
        try {
            return registry.write(name, content);
        } catch (ToolInputException e) {
            return "错误: " + e.getMessage();
        } catch (RuntimeException e) {
            log.warn("写入记忆失败: {}", e.getMessage());
            return "错误: 写入记忆失败: " + e.getMessage();
        }
    }

    @Tool(name = "memory_delete", description =
            "删除一条记忆文件。name 为相对记忆库根的路径(省略扩展名时默认 .md)。" +
            "重要:删除后须同步从 MEMORY.md 及所属板块文件的索引中移除对应条目。")
    public String memoryDelete(
            @ToolParam(description = "要删除的记忆名称/相对路径") String name) {
        try {
            return registry.delete(name);
        } catch (ToolInputException e) {
            return "错误: " + e.getMessage();
        } catch (RuntimeException e) {
            log.warn("删除记忆失败: {}", e.getMessage());
            return "错误: 删除记忆失败: " + e.getMessage();
        }
    }
}
