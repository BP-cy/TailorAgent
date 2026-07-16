package com.changy.tailoragent.tool.support;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件读取状态表 —— Read / Edit / Write 闭环的命门。
 * <p>
 * 复刻 Claude Code 的 {@code toolUseContext.readFileState}:工具要求"先 Read 再改",
 * 并据此做<b>写前防陈旧</b>(read 之后文件被用户/外部改动 → 拒绝写,要求重读)。
 * <p>
 * 作用域:本应用通常单会话串行使用,故用进程级单例,按规范化绝对路径作 key。
 * 内容统一以 LF 规范化后的形式存储,便于 Edit/Write 做内容比对。
 */
@Component
public class ReadFileStateService {

    /**
     * 一次读取的快照。
     *
     * @param content  读取到的内容(LF 规范化,全量读时为整文件)
     * @param mtimeMs  读取时文件的最后修改时间(毫秒)
     * @param offset   读取起始行(全量读为 null)
     * @param limit    读取行数(全量读为 null)
     */
    public record Entry(String content, long mtimeMs, Integer offset, Integer limit) {
        /** 是否为全量读取(无 offset/limit)。 */
        public boolean isFullRead() {
            return offset == null && limit == null;
        }
    }

    private final Map<String, Entry> state = new ConcurrentHashMap<>();

    private static String key(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    /** 记录一次成功读取。 */
    public void recordRead(Path path, String content, long mtimeMs, Integer offset, Integer limit) {
        state.put(key(path), new Entry(content, mtimeMs, offset, limit));
    }

    /** 取某文件的读取快照。 */
    public Optional<Entry> get(Path path) {
        return Optional.ofNullable(state.get(key(path)));
    }

    /** 是否读过该文件。 */
    public boolean hasRead(Path path) {
        return state.containsKey(key(path));
    }
}
