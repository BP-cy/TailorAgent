package com.changy.tailoragent.chat.service.impl;

import com.changy.tailoragent.ModelConfig.dto.AppConfig;
import com.changy.tailoragent.ModelConfig.dto.ChatModel;
import com.changy.tailoragent.ModelConfig.dto.ChatRequest;
import com.changy.tailoragent.ModelConfig.dto.CompactionResult;
import com.changy.tailoragent.ModelConfig.dto.ContextConfig;
import com.changy.tailoragent.ModelConfig.service.AppConfigService;
import com.changy.tailoragent.ModelConfig.service.ModelManager;
import com.changy.tailoragent.ModelConfig.service.ReasoningStreamRegistry;
import com.changy.tailoragent.chat.entity.ChatEvent;
import com.changy.tailoragent.chat.entity.ChatTurn;
import com.changy.tailoragent.chat.service.ChatContextProjector;
import com.changy.tailoragent.chat.service.ChatService;
import com.changy.tailoragent.chat.service.InTurnBudget;
import com.changy.tailoragent.chat.service.SessionCompactionService;
import com.changy.tailoragent.chat.service.SessionService;
import com.changy.tailoragent.chat.service.TokenEstimator;
import com.changy.tailoragent.chat.service.ToolCallSink;
import com.changy.tailoragent.chat.service.WorkingSetService;
import com.changy.tailoragent.chat.service.TurnControlRegistry;
import com.changy.tailoragent.chat.service.TurnControlRegistry.RunHandle;
import com.changy.tailoragent.common.exception.BusinessException;
import com.changy.tailoragent.mcp.service.ToolAggregator;
import com.changy.tailoragent.tool.memory.MemoryRegistry;
import com.changy.tailoragent.tool.skill.SkillRegistry;
import com.changy.tailoragent.tool.support.WorkspacePathResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 聊天服务实现(SSE 流式)。
 * <p>
 * 根据请求中的 {@code modelIndex} 从配置列表中选取模型(用户可在 ChatPanel 下拉框切换);
 * 会话历史由后端从数据库投影,前端只发送本轮输入。
 * <p>
 * 一轮对话的落库 + 推送流程(在后台线程执行,避免阻塞 HTTP 请求线程):
 * 必要时建会话 → 开轮次(running) → 落用户事件 → emit start →
 * 投影历史 → .stream() 流式调模型(框架内部自动跑工具循环,装饰器经 {@link ToolCallSink} 实时落库 +
 * 推送 tool_call / tool_result;同时逐块推送 reasoning / text 增量) → 流结束后整体落库助手思考/正文 →
 * 收尾轮次(done/error) → emit done/error。
 */
@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    /** 对话 System Prompt 文件路径(classpath:prompts/ 下的 Markdown,统一管理提示词文本) */
    private static final String PROMPT_CHAT_SYSTEM = "classpath:prompts/chat-system.md";
    /** 提示词模板中的工作区根占位符,每轮请求用当前工作区绝对路径替换 */
    private static final String PH_WORKSPACE_ROOT = "{{WORKSPACE_ROOT}}";
    /** 提示词模板中的「可用 Skills」清单占位符,每轮请求用 SkillRegistry 渲染的清单替换 */
    private static final String PH_SKILLS = "{{SKILLS}}";
    /** 提示词模板中的「当前记忆索引」占位符,每轮请求用 MemoryRegistry 渲染的 MEMORY.md 替换 */
    private static final String PH_MEMORY = "{{MEMORY}}";

    /** 事件负载 JSON 序列化(线程安全,可复用) */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 估算输入的真实 token 锚定校准因子的取值上下限(k = 真实promptTokens / 估算estInput)。
     * 约束在合理区间,防止单次异常用量把阈值判断带偏。
     */
    private static final double ANCHOR_K_MIN = 0.5;
    private static final double ANCHOR_K_MAX = 2.0;

    private final ModelManager modelManager;
    private final AppConfigService configService;
    private final SessionService sessionService;
    private final ChatContextProjector projector;
    private final ToolAggregator toolAggregator;
    private final WorkspacePathResolver workspacePathResolver;
    /** Skill 注册表:渲染「可用 Skills」清单进系统提示词(body 由 skill 工具按需懒加载) */
    private final SkillRegistry skillRegistry;
    /** 记忆库注册表:渲染 MEMORY.md 总索引进系统提示词(条目由 memory_read 工具按需懒加载) */
    private final MemoryRegistry memoryRegistry;
    /** 思考内容旁路缓冲:本轮请求注入关联头 → ReasoningSseTap 截获 reasoning_content → 此处 drain 推送 */
    private final ReasoningStreamRegistry reasoningRegistry;
    /** 轮次取消注册表:登记每个在跑轮次的可取消句柄,供用户主动停止 */
    private final TurnControlRegistry turnControl;
    /** 上下文 token 估算:补「本轮投影 delta」+ 无 usage 时兜底 */
    private final TokenEstimator tokenEstimator;
    /** 工作集投影:把最近 Read/Edit/Write 的文件以磁盘最新内容注入每轮上下文 */
    private final WorkingSetService workingSetService;
    /** 上下文压缩:把较早对话压成摘要,降低长会话的输入 token */
    private final SessionCompactionService compactionService;

    /** 消费队列的「流正常结束」哨兵(与 ChatResponse / Throwable 区分) */
    private static final Object STREAM_DONE = new Object();

    /** 对话 System Prompt 模板(启动时从 classpath 加载一次,含 {@link #PH_WORKSPACE_ROOT} 占位) */
    private final String chatSystemTemplate;

    /** 跑阻塞式 .call() 的后台线程池(daemon):Controller 立即返回 emitter,真正执行在此 */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "chat-stream");
        t.setDaemon(true);
        return t;
    });

    public ChatServiceImpl(ModelManager modelManager,
                           AppConfigService configService,
                           SessionService sessionService,
                           ChatContextProjector projector,
                           ToolAggregator toolAggregator,
                           WorkspacePathResolver workspacePathResolver,
                           SkillRegistry skillRegistry,
                           MemoryRegistry memoryRegistry,
                           ReasoningStreamRegistry reasoningRegistry,
                           TurnControlRegistry turnControl,
                           TokenEstimator tokenEstimator,
                           WorkingSetService workingSetService,
                           SessionCompactionService compactionService,
                           ResourceLoader resourceLoader) {
        this.modelManager = modelManager;
        this.configService = configService;
        this.sessionService = sessionService;
        this.projector = projector;
        this.toolAggregator = toolAggregator;
        this.workspacePathResolver = workspacePathResolver;
        this.skillRegistry = skillRegistry;
        this.memoryRegistry = memoryRegistry;
        this.reasoningRegistry = reasoningRegistry;
        this.turnControl = turnControl;
        this.tokenEstimator = tokenEstimator;
        this.workingSetService = workingSetService;
        this.compactionService = compactionService;
        this.chatSystemTemplate = loadPrompt(resourceLoader, PROMPT_CHAT_SYSTEM);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    @Override
    public void chat(ChatRequest request, SseEmitter emitter) {
        // 提交后台线程执行;阻塞式 .call() 不能跑在 HTTP 请求线程上(否则无法流式推送)。
        // 句柄随提交一并创建并回填 Future,使该轮可被用户主动取消(见 TurnControlRegistry)。
        RunHandle handle = new RunHandle();
        Future<?> future = executor.submit(() -> runChat(request, emitter, handle));
        handle.setFuture(future);
    }

    /**
     * 手动压缩会话上下文(同步,非流式)。用所选模型生成摘要、落库 summary 事件,
     * 再重新估算压缩后的完整上下文占用并回填到 compact 轮次,返回给前端刷新占比条。
     */
    @Override
    public CompactionResult compact(Integer sessionId, Integer modelIndex) {
        ChatModel selected = resolveModel(modelIndex == null ? 0 : modelIndex);
        ChatClient client = modelManager.getOrCreate(
                selected.getBaseUrl(), selected.getApiKey(), selected.getModelName());
        var r = compactionService.compact(
                sessionId, client, selected.getModelName(),
                configService.getConfig().getContext().getProtectRecentTurns(), "manual");

        CompactionResult dto = new CompactionResult();
        dto.setCompacted(r.compacted());
        dto.setTokensBefore(r.tokensBefore());
        dto.setTokensAfter(r.tokensAfter());
        if (!r.compacted()) {
            dto.setMessage("历史较短,暂无需压缩");
            return dto;
        }
        // 重新估算压缩后的完整上下文(系统提示词 + 投影历史 + 工作集),落到 compact 轮次用量 + 返回
        String systemPrompt = buildSystemPrompt();
        List<Message> messages = buildContext(sessionId, selected);
        int contextTokens = tokenEstimator.estimateMessages(systemPrompt, messages);
        recordUsage(r.compactTurn(), selected.getModelName(), null, null, contextTokens, contextTokens);
        dto.setContextTokens(contextTokens);
        dto.setMessage("已压缩:较早对话 ~" + r.tokensBefore() + " → 摘要 ~" + r.tokensAfter() + " tokens");
        log.info("手动压缩完成: sessionId={}, contextTokens≈{}", sessionId, contextTokens);
        return dto;
    }

    /**
     * 拼装本轮上下文消息:从事件流投影历史(应用压缩摘要)+ 注入工作集(磁盘最新文件内容)。
     * 工作集插在「当前用户消息」之前,作为刚刷新的现状紧贴请求。
     */
    private List<Message> buildContext(Integer sessionId, ChatModel selected) {
        List<ChatEvent> history = sessionService.loadEvents(sessionId);
        List<Message> messages = projector.project(history);
        Integer ctxLen = selected.getContextLength();
        int wsBudget = (ctxLen != null && ctxLen > 0)
                ? (int) (ctxLen * configService.getConfig().getContext().getWorkingSetRatio()) : 0;
        workingSetService.build(history, wsBudget)
                .ifPresent(ws -> messages.add(Math.max(0, messages.size() - 1), ws));
        log.info("投影层: 事件 {} 条 → 上下文消息 {} 条(text/skill_context/摘要 + 工作集)",
                history.size(), messages.size());
        return messages;
    }

    /** 一轮对话的完整执行(后台线程) */
    private void runChat(ChatRequest request, SseEmitter emitter, RunHandle handle) {
        // 1) 选模型并校验 —— 放在建会话之前,避免配置错误时产生孤儿会话
        ChatModel selected;
        try {
            selected = resolveModel(request.getModelIndex());
        } catch (RuntimeException ex) {
            sendError(emitter, ex.getMessage());
            return;
        }

        // 2) 会话:空 sessionId 则新建(标题取首条用户消息)
        Integer sessionId = request.getSessionId();
        if (sessionId == null) {
            sessionId = sessionService.createSession(request.getContent()).getId();
        }

        // 3) 开轮次 + 落用户事件
        ChatTurn turn = sessionService.startTurn(sessionId, "qa");
        // 立即登记可取消句柄:前端在下面的 start 事件里拿到 turnId,据此可随时取消本轮
        turnControl.register(turn.getId(), handle);
        sessionService.appendTextEvent(turn, "user", request.getContent());

        // 4) 告知前端会话/轮次 id(新建会话场景下前端据此选中 + 刷新列表)
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("sessionId", sessionId);
        start.put("turnId", turn.getId());
        sendEvent(emitter, "start", start);

        // 5) 系统提示词(本轮一次性构建,占位符替换后即最终文本);每轮打印,便于追踪变化
        String systemPrompt = buildSystemPrompt();
        log.info("系统提示词(本轮, 共 {} 字):\n{}", systemPrompt.length(), systemPrompt);

        // 5.1) 投影历史(应用压缩摘要)+ 工作集,拼成本轮上下文消息
        List<Message> messages = buildContext(sessionId, selected);

        // 5.2) 真实 token 锚定 + 自动压缩:估算本轮总输入,用上一轮真实 promptTokens 校准估算偏差(锚定),
        //      锚定后超过窗口阈值则先压缩较早历史,再用压缩后的事件流重建上下文。失败不阻断对话(降级为不压缩)。
        Integer ctxLen = selected.getContextLength();
        ContextConfig cfg = configService.getConfig().getContext();
        double k = anchorFactor(sessionId);
        // estInput:本轮实际发送内容的「原始估算」(供下一轮锚定记录到 usage_json);
        // anchoredInput:估算 × 锚定因子,作为本轮真实输入的最佳猜测(阈值判断 + 轮内预算基线)
        int estInput = tokenEstimator.estimateMessages(systemPrompt, messages);
        int anchoredInput = (int) (estInput * k);
        if (ctxLen != null && ctxLen > 0) {
            int threshold = (int) (ctxLen * cfg.getAutoCompactRatio());
            if (anchoredInput > threshold) {
                log.info("自动压缩触发: 估算 {} × 锚定k {} = {} tokens > 阈值 {}({}×{}),压缩较早历史",
                        estInput, String.format("%.2f", k), anchoredInput, threshold, ctxLen, cfg.getAutoCompactRatio());
                try {
                    ChatClient compactClient = modelManager.getOrCreate(
                            selected.getBaseUrl(), selected.getApiKey(), selected.getModelName());
                    var r = compactionService.compact(
                            sessionId, compactClient, selected.getModelName(), cfg.getProtectRecentTurns(), "auto");
                    if (r.compacted()) {
                        messages = buildContext(sessionId, selected); // 用压缩后的事件流重建
                        estInput = tokenEstimator.estimateMessages(systemPrompt, messages);
                        anchoredInput = (int) (estInput * k);
                    }
                } catch (RuntimeException ex) {
                    log.warn("自动压缩失败,本轮按未压缩继续: {}", ex.getMessage());
                }
            }
        }

        log.info("=== 对话请求: sessionId={}, turnId={}, modelIndex={}, displayName={}, 上下文消息={}条 ===",
                sessionId, turn.getId(), request.getModelIndex(), selected.getDisplayName(), messages.size());

        // 6) 聚合工具 + 调模型;失败则标记轮次 error 并 emit error
        // 本轮思考旁路 id:注入请求头,让 ReasoningSseTap 把截获的 reasoning_content 投到本轮队列;finally 清理
        String reqId = UUID.randomUUID().toString();
        reasoningRegistry.register(reqId);
        try {
            ChatClient client = modelManager.getOrCreate(
                    selected.getBaseUrl(), selected.getApiKey(), selected.getModelName());
            ToolCallback[] tools = toolAggregator.resolveAll();

            var promptSpec = client.prompt().system(systemPrompt).messages(messages);
            // 开启思考模式 + 关联思考旁路:
            //   - reasoningEffort / extraBody(thinking):请求体顶层字段,驱动 DeepSeek 等 reasoning 模型输出思考
            //   - customHeaders 注入 reqId:Spring AI 2.0.0 流式会丢弃 reasoning_content,改由 ReasoningSseTap
            //     从原始 SSE 截获并按此 id 回填(见 ReasoningStreamRegistry)
            //   - streamUsage(true):让 OpenAI 兼容端在流式末块附带 usage(token 用量),用于上下文占比统计
            var optionsBuilder = OpenAiChatOptions.builder()
                    .model(selected.getModelName())
                    .reasoningEffort("high")
                    .streamUsage(true)
                    .extraBody(Map.of("thinking", Map.of("type", "enabled")))
                    .customHeaders(Map.of(ReasoningStreamRegistry.HEADER, reqId));
            promptSpec = promptSpec.options(optionsBuilder);

            // 逐块累积的「思考」与「正文」缓冲(先于 sink 声明,供分段器捕获)
            StringBuilder reasoningBuf = new StringBuilder();
            StringBuilder textBuf = new StringBuilder();
            int[] reasoningTotal = {0}; // 仅用于完成日志的思考总字数统计

            // 思考分段器:把当前累积的思考落成一条独立 reasoning 事件 —— 先 drain 旁路残余补尾,
            // 再 appendReasoningEvent 并清空缓冲。在「每次工具调用边界」(由 sink 在写 tool_call 前回调)
            // 与「流末」各调一次,使每段思考独立成卡、按顺序穿插于工具事件之间(DB 重放与实时流一致)。
            Runnable segmentReasoning = () -> {
                flushReasoning(reqId, reasoningBuf, emitter); // drain 残余 + 实时补推,避免段尾丢字
                if (reasoningBuf.length() > 0) {
                    reasoningTotal[0] += reasoningBuf.length();
                    sessionService.appendReasoningEvent(turn, reasoningBuf.toString());
                    reasoningBuf.setLength(0);
                }
            };

            if (tools.length > 0) {
                // 本轮 sink:工具装饰器经 ToolContext 取到它,实时落库 + 推送工具事件;
                // onBeforeToolCall=segmentReasoning:工具调用即一段思考的终点,先把思考落库再写 tool_call
                ToolCallSink sink = new SseToolCallSink(sessionService, turn, emitter, MAPPER, segmentReasoning);
                // 同时传入本轮取消句柄:BashTool 据此登记正在跑的前台进程,使用户取消能立即强杀
                Map<String, Object> toolCtx = new HashMap<>();
                toolCtx.put(ToolCallSink.KEY, sink);
                toolCtx.put(TurnControlRegistry.CTX_KEY, handle);
                // 轮内工具输出预算:软上限 = 窗口阈值 − 本轮(锚定)基线输入;装饰器据此对每次结果封顶,
                // 累计逼近窗口时收紧后续输出并提示模型收尾。无窗口长度信息时不收紧(软上限设极大)。
                int softLimit = (ctxLen != null && ctxLen > 0)
                        ? (int) (ctxLen * cfg.getAutoCompactRatio()) - anchoredInput
                        : Integer.MAX_VALUE;
                toolCtx.put(InTurnBudget.CTX_KEY, new InTurnBudget(
                        softLimit, cfg.getMaxToolResultTokens(), cfg.getTightenedToolResultTokens()));
                promptSpec = promptSpec
                        .tools((Object[]) tools)
                        .toolContext(toolCtx);
                log.info("已挂载 {} 个工具 (轮内工具输出软上限 {} tokens / 单次封顶 {})",
                        tools.length, softLimit, cfg.getMaxToolResultTokens());
            }

            // 流式拿响应:逐块累积「思考」与「正文」,边收边推增量 SSE。
            // 思考(reasoning_content)由 ReasoningSseTap 从原始 SSE 截获、按 reqId 投入队列,这里每块 drain 出来推送
            //（Spring AI 2.0.0 流式会丢弃 reasoning_content,不能依赖 AssistantMessage 的 reasoningContent 元数据);
            // 正文仍走框架增量。工具调用循环在框架内部自动跑,装饰器经 sink 实时推送 tool_call/tool_result。
            //
            // 取消支持:改用「显式 subscribe + 阻塞队列」消费,持有 Disposable 句柄 ——
            // 用户取消时 dispose() 立刻断开到模型的 HTTP 流,future.cancel(true) 中断本线程的 queue.take()。
            // 工具调用仍在框架内部的 boundedElastic 线程跑(本线程阻塞等流恢复时它在执行),与原来一致。
            int chunks = 0;
            // 模型返回的真实用量(流式末块携带,choices 为空):promptTokens 即「本轮发出的输入」token,
            // 已精确吸收截至上一轮的全部投影历史。null 表示该端点未回 usage(走兜底估算)。
            Integer promptTokens = null;
            Integer completionTokens = null;

            Flux<ChatResponse> flux = promptSpec.stream().chatResponse();
            BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
            Disposable subscription = flux.subscribe(
                    queue::add,                    // onNext:ChatResponse
                    queue::add,                    // onError:Throwable(同入队,消费侧识别后处理)
                    () -> queue.add(STREAM_DONE)); // onComplete:哨兵
            handle.setStream(subscription);

            boolean cancelled = false;
            while (true) {
                Object item;
                try {
                    item = queue.take(); // 阻塞;可被 future.cancel(true) 中断
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    cancelled = true;
                    break;
                }
                if (item == STREAM_DONE) {
                    break;
                }
                if (handle.isCancelled()) {
                    cancelled = true;
                    break;
                }
                if (item instanceof Throwable t) {
                    if (t instanceof RuntimeException re) {
                        throw re;
                    }
                    throw new RuntimeException(t);
                }

                ChatResponse chunk = (ChatResponse) item;
                // 用量捕获:须在 out==null 短路之前 —— usage 末块的 choices 为空(getResult()==null),
                // 若放到下面会被 continue 跳过。取最后一次非空 usage 即可。
                var usage = chunk.getMetadata() != null ? chunk.getMetadata().getUsage() : null;
                if (usage != null && usage.getPromptTokens() != null) {
                    promptTokens = usage.getPromptTokens().intValue();
                    if (usage.getCompletionTokens() != null) {
                        completionTokens = usage.getCompletionTokens().intValue();
                    }
                }
                // 思考增量:HTTP 旁路截获的 reasoning_content(2.0.0 流式下唯一可靠来源)
                flushReasoning(reqId, reasoningBuf, emitter);

                AssistantMessage out = chunk.getResult() != null ? chunk.getResult().getOutput() : null;
                if (out == null) {
                    continue;
                }
                chunks++;
                // 兜底:个别 OpenAI 兼容实现可能仍把思考归一化进元数据,有则一并收(2.0.0 DeepSeek 流式恒为空)
                String rDelta = extractReasoning(out);
                if (rDelta != null && !rDelta.isEmpty()) {
                    reasoningBuf.append(rDelta);
                    emitDelta(emitter, "reasoning", rDelta);
                }
                // 正文增量
                String tDelta = out.getText();
                if (tDelta != null && !tDelta.isEmpty()) {
                    textBuf.append(tDelta);
                    emitDelta(emitter, "text", tDelta);
                }
            }

            // 被用户取消:进程/流已由 abort() 拆除,这里只负责落 cancelled 状态 + 通知前端
            if (cancelled || handle.isCancelled()) {
                finishCancelled(turn, sessionId, emitter);
                return;
            }

            // 流末:把最后一段思考(若有)落成独立 reasoning 事件 —— 与工具边界同一逻辑,内含兜底 drain
            segmentReasoning.run();
            log.info("流式完成: chunks={}, reasoning={}chars, text={}chars",
                    chunks, reasoningTotal[0], textBuf.length());

            // 7) 流结束后落正文:思考已按段在「工具边界 / 流末」实时落库(每段一条 reasoning 事件,
            //    排在对应 tool_call 之前),此处仅补最终正文。DB 重放顺序与实时流一致。
            sessionService.appendTextEvent(turn, "assistant", textBuf.toString());

            // 8) 统计上下文占用 = 本轮真实输入用量 + 本轮新进入投影层的助手正文(下一轮才会被投影,故此处预加)。
            //    reasoning / tool 事件不投影,不计入。无 usage 时整段兜底估算,保证 UI 永远有值。
            int replyDelta = tokenEstimator.estimate(textBuf.toString());
            int contextTokens = (promptTokens != null)
                    ? promptTokens + replyDelta
                    : tokenEstimator.estimateMessages(systemPrompt, messages) + replyDelta;
            // estInput 同存:本轮发送内容的原始估算,供下一轮用真实 promptTokens 校准(锚定)
            recordUsage(turn, selected.getModelName(), promptTokens, completionTokens, contextTokens, estInput);

            // 9) 收尾 + emit done(带 contextTokens,前端据此即时刷新占比条)
            sessionService.finishTurn(turn, "done");
            Map<String, Object> done = new LinkedHashMap<>();
            done.put("sessionId", sessionId);
            done.put("contextTokens", contextTokens);
            sendEvent(emitter, "done", done);
            emitter.complete();
            log.info("对话成功: sessionId={}, turnId={}, model={}", sessionId, turn.getId(), selected.getDisplayName());
        } catch (RuntimeException ex) {
            // 取消过程中(dispose 等)抛出的异常按取消收尾,不上报错误
            if (handle.isCancelled()) {
                finishCancelled(turn, sessionId, emitter);
            } else {
                sessionService.finishTurn(turn, "error");
                log.error("对话调用失败: sessionId={}, turnId={}, err={}", sessionId, turn.getId(), ex.getMessage());
                sendError(emitter, ex.getMessage());
            }
        } finally {
            reasoningRegistry.unregister(reqId);
            turnControl.remove(turn.getId());
        }
    }

    /**
     * 取消收尾:把轮次置 cancelled 并向前端发 {@code cancelled} 事件后关闭流。
     * <p>本轮已落库的用户/工具/思考事件保留;尚未落库的最终助手正文丢弃(轮次视为未完成)。
     * 先清除线程中断标志,避免随后的 DB 写入被 JDBC 当作中断中止。
     */
    private void finishCancelled(ChatTurn turn, Integer sessionId, SseEmitter emitter) {
        Thread.interrupted(); // 清除中断标志
        sessionService.finishTurn(turn, "cancelled");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId);
        sendEvent(emitter, "cancelled", payload);
        try {
            emitter.complete();
        } catch (RuntimeException ignore) {
            // 前端可能已主动 abort 连接,complete 失败可忽略
        }
        log.info("对话已取消: sessionId={}, turnId={}", sessionId, turn.getId());
    }

    /**
     * 组装并落库本轮用量 JSON(promptTokens/completionTokens/contextTokens/estInput);序列化失败不影响主流程。
     * <p>{@code estInput} 是本轮发送内容的字符级原始估算,与真实 {@code promptTokens} 同存,
     * 供下一轮算锚定因子 {@code k = promptTokens / estInput} 校准估算偏差(见 {@link #anchorFactor})。
     */
    private void recordUsage(ChatTurn turn, String model, Integer promptTokens,
                             Integer completionTokens, int contextTokens, Integer estInput) {
        Map<String, Object> usage = new LinkedHashMap<>();
        if (promptTokens != null) usage.put("promptTokens", promptTokens);
        if (completionTokens != null) usage.put("completionTokens", completionTokens);
        usage.put("contextTokens", contextTokens);
        if (estInput != null) usage.put("estInput", estInput);
        try {
            sessionService.recordUsage(turn, model, MAPPER.writeValueAsString(usage));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("序列化用量失败: turnId={}, err={}", turn.getId(), e.getMessage());
        }
    }

    /**
     * 真实 token 锚定因子:用该会话上一轮的真实 {@code promptTokens} 与当时的估算 {@code estInput}
     * 之比,校准本轮字符级估算的系统性偏差(估算未含工具 schema 等,通常偏低)。
     * 约束在 [{@value #ANCHOR_K_MIN}, {@value #ANCHOR_K_MAX}];无可用历史则返回 1.0(不校准)。
     */
    private double anchorFactor(Integer sessionId) {
        var anchor = sessionService.latestAnchor(sessionId);
        if (anchor.promptTokens() == null || anchor.estInput() == null || anchor.estInput() <= 0) {
            return 1.0;
        }
        double k = (double) anchor.promptTokens() / anchor.estInput();
        return Math.max(ANCHOR_K_MIN, Math.min(ANCHOR_K_MAX, k));
    }

    /**
     * 取走本轮旁路缓冲的思考增量(若有)→ 累积 + 推送 reasoning 事件。
     * <p>调用点有二:① 主流逐块循环(chat-stream 线程);② segmentReasoning 工具边界落库前
     * (boundedElastic 线程,工具执行所在)。两者**不并发**——工具执行期间主流阻塞在 toIterable().next()
     * 等流恢复,故 emitter.send 不会两线程同时进行;registry 又是并发队列,跨线程 drain 安全。
     */
    private void flushReasoning(String reqId, StringBuilder reasoningBuf, SseEmitter emitter) {
        String delta = reasoningRegistry.drain(reqId);
        if (delta != null && !delta.isEmpty()) {
            reasoningBuf.append(delta);
            emitDelta(emitter, "reasoning", delta);
        }
    }

    // ==================== SSE 发送 ====================

    /** 发送一条具名事件,data 为 payload 的 JSON 单行字符串 */
    private void sendEvent(SseEmitter emitter, String name, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(name).data(MAPPER.writeValueAsString(payload)));
        } catch (IOException | RuntimeException e) {
            log.warn("[对话] SSE 发送失败: name={}, err={}", name, e.getMessage());
        }
    }

    /** 发送一条增量内容事件(reasoning / text 逐块),data 为 {@code {content:<delta>}} */
    private void emitDelta(SseEmitter emitter, String name, String delta) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("content", delta);
        sendEvent(emitter, name, m);
    }

    /** 发送 error 事件并关闭流(与 ai-edit 一致的 {message} 结构) */
    private void sendError(SseEmitter emitter, String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("message", message != null ? message : "对话失败");
        try {
            emitter.send(SseEmitter.event().name("error").data(MAPPER.writeValueAsString(err)));
            emitter.complete();
        } catch (IOException | RuntimeException e) {
            log.warn("[对话] SSE error 事件发送失败: {}", e.getMessage());
            emitter.completeWithError(e);
        }
    }

    /**
     * 从助手消息元数据中取思考内容。
     * <p>
     * reasoning 模型(DeepSeek-R1、Qwen-thinking 等)的非标准字段 {@code reasoning_content},
     * 被 Spring AI 的 OpenAI 模块统一归一化到 {@code AssistantMessage.metadata} 的 {@code reasoningContent}。
     * 普通模型该字段不存在或为空,返回 null。
     */
    private static String extractReasoning(AssistantMessage out) {
        if (out == null) {
            return null;
        }
        Object v = out.getMetadata().get("reasoningContent");
        return v == null ? null : v.toString();
    }

    // ==================== Prompt / 模型 ====================

    /**
     * 构造系统提示词:把模板里的工作区根占位替换为**当前工作区的绝对路径**,
     * 减少模型凭空猜测 {@code /home/user/...} 之类的类 Unix 路径而被写入沙箱拦截、再重试的浪费。
     * 工作区根来自 {@link WorkspacePathResolver#defaultRoot()}(实时读 {@code config.workingDir},
     * 空则兜底容器目录),与文件工具的实际沙箱根**完全一致**。
     */
    private String buildSystemPrompt() {
        String root = workspacePathResolver.defaultRoot().toString();
        return chatSystemTemplate
                .replace(PH_WORKSPACE_ROOT, root)
                .replace(PH_SKILLS, skillRegistry.renderCatalog())
                .replace(PH_MEMORY, memoryRegistry.renderIndex());
    }

    /** 从 classpath 加载提示词文本(启动时调用一次;沿用 AiEditService 的约定,UTF-8) */
    private String loadPrompt(ResourceLoader resourceLoader, String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("提示词文件不存在: " + location);
        }
        try {
            String content = resource.getContentAsString(StandardCharsets.UTF_8);
            log.info("加载提示词: {} ({} chars)", location, content.length());
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("加载提示词失败: " + location, e);
        }
    }

    /** 按索引从配置中取出可用模型并校验 API Key */
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
}
