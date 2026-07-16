package com.changy.tailoragent.mcp.config;

import com.changy.tailoragent.mcp.tool.CommonTool;
import com.changy.tailoragent.tool.file.FileEditTool;
import com.changy.tailoragent.tool.file.FileReadTool;
import com.changy.tailoragent.tool.file.FileWriteTool;
import com.changy.tailoragent.tool.file.GlobTool;
import com.changy.tailoragent.tool.memory.MemoryTool;
import com.changy.tailoragent.tool.knowledge.KnowledgeSearchTool;
import com.changy.tailoragent.tool.search.GrepTool;
import com.changy.tailoragent.tool.shell.BashOutputTool;
import com.changy.tailoragent.tool.shell.BashTool;
import com.changy.tailoragent.tool.shell.KillShellTool;
import com.changy.tailoragent.tool.skill.SkillTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 本地工具 Bean 注册 —— 将所有含 {@code @Tool} 方法的 Bean 收集到一个列表中，
 * 供 {@link com.changy.tailoragent.mcp.service.ToolAggregator} 统一聚合。
 * <p>
 * 新增工具类时：1) 创建 {@code @Component} 类并标注 {@code @Tool} 方法；
 * 2) 在此处将其加入 {@code localToolBeans} 列表。
 */
@Configuration
public class ToolConfig {

    @Bean
    public List<Object> localToolBeans(CommonTool commonTool,
                                       FileReadTool fileReadTool,
                                       FileWriteTool fileWriteTool,
                                       FileEditTool fileEditTool,
                                       GlobTool globTool,
                                       GrepTool grepTool,
                                       BashTool bashTool,
                                       BashOutputTool bashOutputTool,
                                       KillShellTool killShellTool,
                                       SkillTool skillTool,
                                       MemoryTool memoryTool,
                                       KnowledgeSearchTool knowledgeSearchTool) {
        return List.of(commonTool,
                fileReadTool, fileWriteTool, fileEditTool, globTool, grepTool,
                bashTool, bashOutputTool, killShellTool,
                skillTool, memoryTool, knowledgeSearchTool);
    }
}
