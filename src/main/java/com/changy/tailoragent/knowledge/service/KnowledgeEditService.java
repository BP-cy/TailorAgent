package com.changy.tailoragent.knowledge.service;

import com.changy.tailoragent.ModelConfig.dto.AppConfig;
import com.changy.tailoragent.ModelConfig.dto.ChatModel;
import com.changy.tailoragent.ModelConfig.dto.ContextConfig;
import com.changy.tailoragent.ModelConfig.service.AppConfigService;
import com.changy.tailoragent.ModelConfig.service.ModelManager;
import com.changy.tailoragent.ModelConfig.service.ReasoningStreamRegistry;
import com.changy.tailoragent.chat.service.TokenEstimator;
import com.changy.tailoragent.chat.service.ToolCallSink;
import com.changy.tailoragent.common.exception.BusinessException;
import com.changy.tailoragent.knowledge.dto.KbEditRequest;
import com.changy.tailoragent.knowledge.mcp.KbMcpClientManager;
import com.changy.tailoragent.knowledge.skill.KbSkillRegistry;
import com.changy.tailoragent.knowledge.skill.KbSkillTool;
import com.changy.tailoragent.mcp.dto.McpServerConfig;
import com.changy.tailoragent.mcp.service.EventEmittingToolCallback;
import com.changy.tailoragent.tool.knowledge.KnowledgeEditTool;
import com.changy.tailoragent.tool.knowledge.KnowledgeReadTool;
import com.changy.tailoragent.tool.knowledge.KnowledgeVisualizeDataStructureTool;
import com.changy.tailoragent.tool.knowledge.KnowledgeWriteTool;
import com.changy.tailoragent.tool.support.KnowledgePathResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库 AI 编辑服务 —— 一条<b>独立于主对话、不落库</b>的 agent 流式链路。
 *
 * <p>以 {@code ChatServiceImpl.runChat} 为模板的精简版：保留「模型选择 + 系统提示词 + 工具挂载 + SSE 流式
 * 推送（start / tool_call / tool_result / text / done / error）」，剥离全部会话/轮次/事件落库、上下文压缩、
 * token 锚定、工作集。工具集只含显式挂载的知识库读写编辑、数据结构可视化、Skill 与知识编辑专用 MCP，
 * 与主对话工具集隔离。
 * 模型用 {@link EventEmittingToolCallback} 包裹后经 ToolContext 里的
 * {@link KnowledgeToolCallSink} 把工具事件实时推给前端。
 */
@Service
public class KnowledgeEditService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeEditService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PROMPT_LOCATION = "classpath:prompts/knowledge-edit-system.md";
    private static final String PH_KNOWLEDGE_ROOT = "{{KNOWLEDGE_ROOT}}";
    private static final String PH_DOC_PATH = "{{DOC_PATH}}";
    private static final String PH_SKILLS = "{{SKILLS}}";

    private final ModelManager modelManager;
    private final AppConfigService configService;
    private final KnowledgePathResolver pathResolver;
    private final TokenEstimator tokenEstimator;
    private final KnowledgeReadTool kbReadTool;
    private final KnowledgeWriteTool kbWriteTool;
    private final KnowledgeEditTool kbEditTool;
    /** 知识库编辑专用数据结构可视化工具集：每个 renderer 是一个独立 ToolCallback。 */
    private final KnowledgeVisualizeDataStructureTool kbVisualizeDataStructureTool;
    /** 知识库编辑专用 Skill 工具（绑定 KbSkillRegistry，与主对话隔离；工具名仍 skill，不进 localToolBeans） */
    private final KbSkillTool kbSkillTool;
    /** 知识库编辑专用 Skill 注册表：渲染「可用 Skills」清单进系统提示词（body 由 skill 工具按需懒加载） */
    private final KbSkillRegistry kbSkillRegistry;
    /** 知识库编辑专用 MCP 客户端管理器（独立连接池，服务于 kbMcpServers 配置） */
    private final KbMcpClientManager kbMcpClientManager;
    /** 思考内容旁路缓冲:本轮请求注入关联头 → ReasoningSseTap 截获 reasoning_content → 此处 drain 推送(见 chat 同款机制) */
    private final ReasoningStreamRegistry reasoningRegistry;
    private final String systemTemplate;

    /**
     * 在跑编辑的可取消句柄:editId → 运行态。用户主动停止时据此 dispose 模型流 + 收尾 emitter。
     * 文件编辑工具本身无后台任务；可视化工具会受控启动有硬超时的 Python 子进程，统一由
     * ChildProcessGuard 兜底清理。当前取消会立即断模型流，但不会按 editId 单独终止正在执行的渲染进程。
     */
    private final Map<String, EditRun> running = new ConcurrentHashMap<>();

    /** 一次编辑运行的取消句柄:模型流 Disposable + 本轮 emitter + reasoning reqId */
    private record EditRun(Disposable stream, SseEmitter emitter, String reqId) {}

    public KnowledgeEditService(ModelManager modelManager,
                                AppConfigService configService,
                                KnowledgePathResolver pathResolver,
                                TokenEstimator tokenEstimator,
                                KnowledgeReadTool kbReadTool,
                                KnowledgeWriteTool kbWriteTool,
                                KnowledgeEditTool kbEditTool,
                                KnowledgeVisualizeDataStructureTool kbVisualizeDataStructureTool,
                                KbSkillTool kbSkillTool,
                                KbSkillRegistry kbSkillRegistry,
                                KbMcpClientManager kbMcpClientManager,
                                ReasoningStreamRegistry reasoningRegistry,
                                ResourceLoader resourceLoader) {
        this.modelManager = modelManager;
        this.configService = configService;
        this.pathResolver = pathResolver;
        this.tokenEstimator = tokenEstimator;
        this.kbReadTool = kbReadTool;
        this.kbWriteTool = kbWriteTool;
        this.kbEditTool = kbEditTool;
        this.kbVisualizeDataStructureTool = kbVisualizeDataStructureTool;
        this.kbSkillTool = kbSkillTool;
        this.kbSkillRegistry = kbSkillRegistry;
        this.kbMcpClientManager = kbMcpClientManager;
        this.reasoningRegistry = reasoningRegistry;
        this.systemTemplate = loadPrompt(resourceLoader);
    }

    /**
     * 流式执行知识库 AI 编辑：模型自主用 kb 工具读改当前文档，工具/文本事件经 SSE 实时推送。
     * 非阻塞（reactive subscribe），不占用 HTTP 线程；不落库。
     */
    public void streamEdit(KbEditRequest request, SseEmitter emitter) {
        // 1) 选模型 + 校验
        ChatModel model;
        try {
            model = resolveModel(request.modelIndex() == null ? 0 : request.modelIndex());
        } catch (RuntimeException ex) {
            sendError(emitter, ex.getMessage());
            return;
        }
        if (request.docPath() == null || request.docPath().isBlank()) {
            sendError(emitter, "缺少当前文档路径");
            return;
        }

        // 2) reqId：关联 ReasoningSseTap 旁路截获的思考增量（Spring AI 2.0.0 流式会丢 reasoning_content，见 chat 同款机制）
        String reqId = UUID.randomUUID().toString();
        reasoningRegistry.register(reqId);
        // editId：前端生成，用于用户主动取消时精确定位本轮（空则兜底生成）
        String editId = (request.editId() == null || request.editId().isBlank())
                ? UUID.randomUUID().toString() : request.editId();

        // 3) 系统提示词（占位替换）：{{SKILLS}} 渲染编辑专用 Skill 清单（照抄 chat 的 catalog 机制）
        String root = pathResolver.defaultRoot().toString();
        String systemPrompt = systemTemplate
                .replace(PH_KNOWLEDGE_ROOT, root)
                .replace(PH_DOC_PATH, request.docPath())
                .replace(PH_SKILLS, kbSkillRegistry.renderCatalog());

        // 4) 工具集：kb 文件工具 + 43 个 renderer 专用可视化工具 + kbSkill（来源 local）+ 编辑专用 MCP（来源 mcp）。
        //    全部逐个包裹事件装饰器，前端 ToolCallCard 直接复用。
        //    注意：此处显式拼装，绝不调 ToolAggregator.resolveAll()——那会带进主对话的工作区文件工具/Bash（越权）。
        ContextConfig ctx = configService.getConfig().getContext();
        List<ToolCallback> tools = new ArrayList<>();
        for (Object bean : List.of(kbReadTool, kbWriteTool, kbEditTool, kbSkillTool)) {
            for (ToolCallback cb : ToolCallbacks.from(bean)) {
                tools.add(new EventEmittingToolCallback(cb, "local", tokenEstimator,
                        ctx.getMaxToolResultTokens(), ctx.getTightenedToolResultTokens()));
            }
        }
        for (ToolCallback cb : kbVisualizeDataStructureTool.getToolCallbacks()) {
            tools.add(new EventEmittingToolCallback(cb, "local", tokenEstimator,
                    ctx.getMaxToolResultTokens(), ctx.getTightenedToolResultTokens()));
        }
        collectKbMcpTools(tools, ctx); // 编辑专用 MCP（kbMcpServers）
        ToolCallback[] toolArr = tools.toArray(new ToolCallback[0]);

        // 5) 本轮 sink（只推送、不落库）：onBeforeToolCall 在写 tool_call 前先 flush 一段思考，
        //    保证 reasoning → tool_call 的到达顺序（前端据此把每段思考独立成卡，见 reasoning-display）
        ToolCallSink sink = new KnowledgeToolCallSink(emitter, MAPPER,
                () -> flushReasoning(reqId, emitter));
        Map<String, Object> toolCtx = new HashMap<>();
        toolCtx.put(ToolCallSink.KEY, sink);

        // 6) emit start（带 editId，供前端停止按钮定位本轮）
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("docPath", request.docPath());
        start.put("editId", editId);
        sendEvent(emitter, "start", start);

        log.info("[知识库编辑] 开始: docPath={}, model={}, 指令={}",
                request.docPath(), model.getModelName(),
                request.instruction() == null ? "" :
                        request.instruction().substring(0, Math.min(80, request.instruction().length())));

        try {
            ChatClient client = modelManager.getOrCreate(
                    model.getBaseUrl(), model.getApiKey(), model.getModelName());
            var promptSpec = client.prompt()
                    .system(systemPrompt)
                    .messages(buildHistory(request.history())) // 跨轮记忆：装入本轮之前的对话历史（不落库）
                    .user(request.instruction() == null ? "" : request.instruction())
                    .options(OpenAiChatOptions.builder()
                            .model(model.getModelName())
                            .customHeaders(Map.of(ReasoningStreamRegistry.HEADER, reqId)))
                    .tools((Object[]) toolArr)
                    .toolContext(toolCtx);

            // 非阻塞流式消费：思考增量每块 drain 先推；文本增量逐块推；工具事件由 sink 在工具执行时推。
            // 持有 Disposable，供用户主动取消时 dispose 断流。
            Disposable disposable = promptSpec.stream().chatResponse().subscribe(
                    chunk -> {
                        flushReasoning(reqId, emitter); // 思考先于正文送达
                        AssistantMessage out = chunk.getResult() != null ? chunk.getResult().getOutput() : null;
                        if (out == null) return;
                        String t = out.getText();
                        if (t != null && !t.isEmpty()) {
                            emitDelta(emitter, "text", t);
                        }
                    },
                    error -> {
                        log.error("[知识库编辑] 流式失败: {}", extractMessage(error), error);
                        cleanup(editId, reqId);
                        sendError(emitter, "AI 编辑失败: " + extractMessage(error));
                    },
                    () -> {
                        flushReasoning(reqId, emitter); // 兜尾：drain 流末残余思考
                        cleanup(editId, reqId);
                        Map<String, Object> done = new LinkedHashMap<>();
                        done.put("docPath", request.docPath());
                        sendEvent(emitter, "done", done);
                        try {
                            emitter.complete();
                        } catch (RuntimeException ignore) {
                            // 前端可能已 abort，忽略
                        }
                        log.info("[知识库编辑] 完成: docPath={}", request.docPath());
                    });
            running.put(editId, new EditRun(disposable, emitter, reqId));
        } catch (RuntimeException ex) {
            log.error("[知识库编辑] 调用失败: {}", ex.getMessage());
            cleanup(editId, reqId);
            sendError(emitter, "AI 编辑失败: " + extractMessage(ex));
        }
    }

    /**
     * 用户主动停止某一编辑轮次：断开模型流（{@link Disposable#dispose()} 立即停连、停计费），
     * 注销思考缓冲，尽力推送 {@code cancelled} 事件并收尾 emitter。找不到（已结束）返回 false。
     * <p>取消会立即断模型流；数据结构可视化可能已启动受硬超时约束的 Python 进程，目前不按 editId
     * 单独强杀该进程，应用退出时仍由 ChildProcessGuard 兜底清理。
     */
    public boolean cancel(String editId) {
        if (editId == null || editId.isBlank()) {
            return false;
        }
        EditRun run = running.remove(editId);
        if (run == null) {
            return false;
        }
        run.stream().dispose();
        reasoningRegistry.unregister(run.reqId());
        try {
            run.emitter().send(SseEmitter.event().name("cancelled")
                    .data(MAPPER.writeValueAsString(Map.of("editId", editId))));
            run.emitter().complete();
        } catch (IOException | RuntimeException e) {
            log.warn("[知识库编辑] 取消收尾失败: {}", e.getMessage());
        }
        log.info("[知识库编辑] 已取消: editId={}", editId);
        return true;
    }

    /** drain 本轮思考增量并推送 reasoning 事件（无内容则不推）。 */
    private void flushReasoning(String reqId, SseEmitter emitter) {
        String delta = reasoningRegistry.drain(reqId);
        if (delta != null && !delta.isEmpty()) {
            emitDelta(emitter, "reasoning", delta);
        }
    }

    /** 结束一轮：移除运行句柄 + 注销思考缓冲队列（幂等）。 */
    private void cleanup(String editId, String reqId) {
        running.remove(editId);
        reasoningRegistry.unregister(reqId);
    }

    // ==================== 私有 ====================

    /**
     * 收集知识库编辑专用的 MCP 工具（来自 {@code kbMcpServers} 配置，经 {@link KbMcpClientManager} 独立连接池），
     * 逐个包裹 {@link EventEmittingToolCallback}（来源 mcp）后加入工具集。
     * <p>逻辑照抄 {@code ToolAggregator.collectMcpTools}，仅数据源换成 kbMcpServers；单个 server 失败只 warn 跳过。
     */
    private void collectKbMcpTools(List<ToolCallback> target, ContextConfig ctx) {
        List<McpServerConfig> servers = configService.getConfig().getKbMcpServers();
        if (servers == null || servers.isEmpty()) {
            return;
        }
        for (McpServerConfig cfg : servers) {
            if (cfg == null || !cfg.isEnabled()) {
                continue;
            }
            try {
                McpSyncClient client = kbMcpClientManager.getOrCreateClient(cfg);
                var provider = new SyncMcpToolCallbackProvider(client);
                for (ToolCallback cb : provider.getToolCallbacks()) {
                    target.add(new EventEmittingToolCallback(cb, "mcp", tokenEstimator,
                            ctx.getMaxToolResultTokens(), ctx.getTightenedToolResultTokens()));
                }
            } catch (Exception e) {
                log.warn("[知识库编辑] MCP 工具加载失败，已跳过: name={}, err={}", cfg.getName(), e.getMessage());
            }
        }
    }

    /**
     * 把前端带来的对话历史转成 Spring AI 消息，装入本轮请求以提供跨轮记忆（历史不落库）。
     * 只收 user / assistant 的非空文本；role 非 assistant 一律按 user 处理（含前端过滤后的兜底）。
     */
    private List<Message> buildHistory(List<KbEditRequest.Message> history) {
        List<Message> msgs = new ArrayList<>();
        if (history == null) {
            return msgs;
        }
        for (KbEditRequest.Message m : history) {
            if (m == null || m.content() == null || m.content().isBlank()) {
                continue;
            }
            msgs.add("assistant".equalsIgnoreCase(m.role())
                    ? new AssistantMessage(m.content())
                    : new UserMessage(m.content()));
        }
        return msgs;
    }

    private ChatModel resolveModel(int index) {
        AppConfig config = configService.getConfig();
        List<ChatModel> models = config.getAvailableChatModels();
        if (models.isEmpty()) {
            throw new BusinessException("请先在设置中添加对话模型");
        }
        if (index < 0 || index >= models.size()) {
            throw new BusinessException("模型索引无效: " + index + "(可用模型共 " + models.size() + " 个)");
        }
        ChatModel selected = models.get(index);
        if (selected.getApiKey() == null || selected.getApiKey().isBlank()) {
            throw new BusinessException("请先在设置中为「" + selected.getDisplayName() + "」填写 API Key");
        }
        return selected;
    }

    private void sendEvent(SseEmitter emitter, String name, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(name).data(MAPPER.writeValueAsString(payload)));
        } catch (IOException | RuntimeException e) {
            log.warn("[知识库编辑] SSE 发送失败: name={}, err={}", name, e.getMessage());
        }
    }

    private void emitDelta(SseEmitter emitter, String name, String delta) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", delta);
        sendEvent(emitter, name, m);
    }

    private void sendError(SseEmitter emitter, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("message", message != null ? message : "AI 编辑失败");
        try {
            emitter.send(SseEmitter.event().name("error").data(MAPPER.writeValueAsString(err)));
            emitter.complete();
        } catch (IOException | RuntimeException e) {
            log.warn("[知识库编辑] SSE error 发送失败: {}", e.getMessage());
            emitter.completeWithError(e);
        }
    }

    private String extractMessage(Throwable t) {
        String m = t.getMessage();
        return (m == null || m.isBlank()) ? t.getClass().getSimpleName() : m;
    }

    private String loadPrompt(ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource(PROMPT_LOCATION);
        if (!resource.exists()) {
            throw new IllegalStateException("提示词文件不存在: " + PROMPT_LOCATION);
        }
        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            log.info("加载提示词: {} ({} chars)", PROMPT_LOCATION, content.length());
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("加载提示词失败: " + PROMPT_LOCATION, e);
        }
    }
}
