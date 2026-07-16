package com.changy.tailoragent.chat.service;

import com.changy.tailoragent.chat.entity.ChatEvent;
import com.changy.tailoragent.chat.entity.ChatSession;
import com.changy.tailoragent.chat.entity.ChatTurn;
import com.changy.tailoragent.chat.mapper.ChatEventMapper;
import com.changy.tailoragent.chat.mapper.ChatSessionMapper;
import com.changy.tailoragent.chat.mapper.ChatTurnMapper;
import com.changy.tailoragent.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 会话持久化业务层 —— 管理 会话 → 轮次 → 事件 三层的读写。
 * <p>
 * 边界约定:本类只负责「存」,不碰模型调用、不碰上下文拼接。
 * 事件流 → LLM 消息的投影由 {@link ChatContextProjector} 单独承担,
 * 二者解耦,保证「存储结构」与「喂给模型的上下文」互不污染。
 */
@Slf4j
@Service
@Order(1) // 启动恢复须晚于建表(DataSourceConfig.initSchema 为 @Order(0))
public class SessionService implements ApplicationRunner {

    private static final DateTimeFormatter ISO_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    /** 标题取首条用户消息的前若干字符 */
    private static final int TITLE_MAX_LEN = 20;

    private final ChatSessionMapper sessionMapper;
    private final ChatTurnMapper turnMapper;
    private final ChatEventMapper eventMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SessionService(ChatSessionMapper sessionMapper,
                          ChatTurnMapper turnMapper,
                          ChatEventMapper eventMapper) {
        this.sessionMapper = sessionMapper;
        this.turnMapper = turnMapper;
        this.eventMapper = eventMapper;
    }

    private static String now() {
        return ISO_FMT.format(LocalDateTime.now());
    }

    /** 启动钩子:容器就绪(且建表完成)后执行残留 running 轮次的兜底恢复。 */
    @Override
    public void run(ApplicationArguments args) {
        recoverRunningTurnsOnStartup();
    }

    /**
     * 启动恢复:把上次进程异常退出(强关/崩溃)时残留的 running 轮次统一刷成 cancelled。
     * <p>
     * 轮次的 running→done/error 收尾依赖 JVM 存活;一旦进程在某轮执行中途被杀,该轮就会
     * 永久卡在 running,导致重开后载入该会话时界面被半截状态拖住。启动时一次性兜底清理,
     * 避免此类「僵尸轮次」。注意:这里只动状态,不回退/删除任何已落库的事件数据。
     */
    public void recoverRunningTurnsOnStartup() {
        List<ChatTurn> stuck = turnMapper.findByStatus("running");
        if (stuck.isEmpty()) {
            return;
        }
        for (ChatTurn t : stuck) {
            turnMapper.updateStatus(t.getId(), "cancelled");
        }
        log.warn("启动恢复:已将 {} 条残留 running 轮次置为 cancelled,turnIds={}",
                stuck.size(), stuck.stream().map(ChatTurn::getId).toList());
    }

    // ==================== 查询 ====================

    /** 会话列表(侧边栏),按最后更新时间倒序 */
    public List<ChatSession> listSessions() {
        return sessionMapper.findAll();
    }

    public ChatSession getSession(Integer id) {
        ChatSession s = sessionMapper.findById(id);
        if (s == null) {
            throw new BusinessException("会话不存在: id=" + id);
        }
        return s;
    }

    /**
     * 整条会话事件流(自增 id 升序)—— <b>含</b>内部事件,供 {@link ChatContextProjector} 投影喂模型。
     * 前端展示请用 {@link #loadVisibleEvents}。
     */
    public List<ChatEvent> loadEvents(Integer sessionId) {
        getSession(sessionId); // 不存在则抛异常
        return eventMapper.findBySession(sessionId);
    }

    /** 前端不可见的内部事件类型(仅供模型上下文,不在界面渲染) */
    private static final java.util.Set<String> INTERNAL_TYPES = java.util.Set.of("skill_context", "summary");

    /**
     * 前端展示用事件流 —— 在 {@link #loadEvents} 基础上过滤掉纯内部上下文事件(如 {@code skill_context}),
     * 这些事件只服务于模型上下文回放,不应在界面重复展示(Skill 加载过程已由工具卡呈现)。
     */
    public List<ChatEvent> loadVisibleEvents(Integer sessionId) {
        return loadEvents(sessionId).stream()
                .filter(e -> !INTERNAL_TYPES.contains(e.getType()))
                .toList();
    }

    // ==================== 写入 ====================

    /** 新建会话,标题取首条用户消息截断生成 */
    @Transactional
    public ChatSession createSession(String firstUserContent) {
        ChatSession s = new ChatSession();
        s.setTitle(buildTitle(firstUserContent));
        String t = now();
        s.setCreatedAt(t);
        s.setUpdatedAt(t);
        sessionMapper.insert(s); // 回填 id
        log.info("新建会话: id={}, title={}", s.getId(), s.getTitle());
        return s;
    }

    /** 开启一个轮次(status=running),seq 为冗余字段,best-effort 填充 */
    @Transactional
    public ChatTurn startTurn(Integer sessionId, String kind) {
        ChatTurn turn = new ChatTurn();
        turn.setSessionId(sessionId);
        turn.setSeq(turnMapper.countBySession(sessionId) + 1);
        turn.setKind(kind);
        turn.setStatus("running");
        turn.setCreatedAt(now());
        turnMapper.insert(turn); // 回填 id
        return turn;
    }

    /** 向轮次追加一条事件;seq 为冗余字段,best-effort 填充 */
    @Transactional
    public ChatEvent appendEvent(ChatTurn turn, String role, String type,
                                 String content, String payload, String status) {
        ChatEvent e = new ChatEvent();
        e.setTurnId(turn.getId());
        e.setSessionId(turn.getSessionId());
        e.setSeq(eventMapper.countByTurn(turn.getId()) + 1);
        e.setRole(role);
        e.setType(type);
        e.setContent(content);
        e.setPayload(payload);
        e.setStatus(status);
        e.setCreatedAt(now());
        eventMapper.insert(e); // 回填 id
        return e;
    }

    /** 追加一条纯文本事件(最常用:user/assistant 的 text) */
    public ChatEvent appendTextEvent(ChatTurn turn, String role, String content) {
        return appendEvent(turn, role, "text", content, null, null);
    }

    /**
     * 追加一条助手思考事件(reasoning),role 固定为 {@code assistant}。
     * <p>
     * 内容来自 reasoning 模型的 {@code reasoningContent}。type 用独立的 {@code reasoning},
     * 与正文 text 区分:前端折叠成「思考过程」卡片;投影层不回灌给模型(详见 {@link ChatContextProjector})。
     */
    public ChatEvent appendReasoningEvent(ChatTurn turn, String content) {
        return appendEvent(turn, "assistant", "reasoning", content, null, null);
    }

    /**
     * 追加一条上下文摘要事件(summary),role 固定为 {@code system}。
     * <p>
     * 由 {@link SessionCompactionService} 压缩较早对话后写入;{@link ChatContextProjector} 投影时
     * 用它替代被覆盖的原文。type 用独立的 {@code summary}(属内部事件,不在前端渲染)。
     * payload 记 {@code coversUpToEventId}(覆盖截止事件 id)等元数据。
     */
    public ChatEvent appendSummaryEvent(ChatTurn turn, String content, String payload) {
        return appendEvent(turn, "system", "summary", content, payload, null);
    }

    /**
     * 追加一条工具事件(tool_call / tool_result),role 固定为 {@code tool}。
     * <p>
     * 工具名 / 来源 / 入参 / 结果等细节全部塞进 {@code payload}(JSON),不开新 type;
     * {@code status} 记 running / success / error,供前端卡片状态与重放使用。
     *
     * @param type    {@code tool_call} 或 {@code tool_result}
     * @param payload 该工具事件的 JSON 负载
     * @param status  工具状态:running / success / error
     */
    public ChatEvent appendToolEvent(ChatTurn turn, String type, String payload, String status) {
        return appendEvent(turn, "tool", type, null, payload, status);
    }

    /** 收尾轮次:置为 done / error / cancelled,并刷新会话更新时间使其在列表置顶 */
    @Transactional
    public void finishTurn(ChatTurn turn, String status) {
        turnMapper.updateStatus(turn.getId(), status);
        sessionMapper.touchUpdatedAt(turn.getSessionId(), now());
    }

    /**
     * 回填本轮使用的模型与 token 用量(usage_json)。
     * <p>用量是「事后冗余」,与轮次状态收尾解耦单独写,失败不影响主流程(仅记日志)。
     */
    public void recordUsage(ChatTurn turn, String model, String usageJson) {
        try {
            turnMapper.updateUsage(turn.getId(), model, usageJson);
        } catch (RuntimeException e) {
            log.warn("回填轮次用量失败: turnId={}, err={}", turn.getId(), e.getMessage());
        }
    }

    /**
     * 取该会话最新一条轮次记录的上下文占用 token(从 usage_json 解析 contextTokens)。
     * 供前端切换/载入会话时回读占比;无记录或解析失败返回 null。
     */
    public Integer latestContextTokens(Integer sessionId) {
        String json = turnMapper.findLatestUsageBySession(sessionId);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode ctx = node.get("contextTokens");
            return ctx != null && ctx.isNumber() ? ctx.asInt() : null;
        } catch (Exception e) {
            log.warn("解析 usage_json 失败: sessionId={}, err={}", sessionId, e.getMessage());
            return null;
        }
    }

    /**
     * 取该会话最新一条 usage_json 里的「真实 promptTokens」与「估算 estInput」配对,
     * 供下一轮用真实用量校准估算偏差(自动压缩阈值锚定)。任一缺失时该项为 null。
     */
    public UsageAnchor latestAnchor(Integer sessionId) {
        String json = turnMapper.findLatestUsageBySession(sessionId);
        if (json == null || json.isBlank()) {
            return UsageAnchor.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode p = node.get("promptTokens");
            JsonNode e = node.get("estInput");
            return new UsageAnchor(
                    p != null && p.isNumber() ? p.asInt() : null,
                    e != null && e.isNumber() ? e.asInt() : null);
        } catch (Exception ex) {
            log.warn("解析 usage_json(锚定)失败: sessionId={}, err={}", sessionId, ex.getMessage());
            return UsageAnchor.empty();
        }
    }

    /** 上一轮用量锚点:真实输入 token 与当时的估算输入(任一可能为 null) */
    public record UsageAnchor(Integer promptTokens, Integer estInput) {
        static UsageAnchor empty() {
            return new UsageAnchor(null, null);
        }
    }

    /**
     * 级联删除会话:先删事件、再删轮次,最后删会话本身。
     * <p>
     * 三表无外键约束,必须手动按「子 → 父」顺序清理,否则会留下无主的轮次/事件孤儿数据
     * (这些孤儿会在下次载入会话时被加载、把界面拖卡)。整段置于同一事务,保证全删或全不删。
     */
    @Transactional
    public void deleteSession(Integer id) {
        getSession(id); // 不存在则抛异常
        int events = eventMapper.deleteBySession(id);
        int turns = turnMapper.deleteBySession(id);
        sessionMapper.deleteById(id);
        log.info("删除会话: id={}, 级联删除轮次={} 事件={}", id, turns, events);
    }

    private String buildTitle(String content) {
        if (content == null || content.isBlank()) {
            return "新会话";
        }
        String oneLine = content.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= TITLE_MAX_LEN
                ? oneLine
                : oneLine.substring(0, TITLE_MAX_LEN) + "…";
    }
}
