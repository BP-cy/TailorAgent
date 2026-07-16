package com.changy.tailoragent.tool.support;

/**
 * 工具文本处理小工具集 —— 行号格式、换行规范化、截断。
 * <p>
 * 行号格式对齐 Claude Code 的 {@code cat -n}:每行 {@code 行号\t内容},行号从 1 起。
 * Edit 的入参 {@code oldString} 必须是去掉行号前缀后的纯内容。
 */
public final class ToolText {

    private ToolText() {
    }

    /** 单行最大字符数,超出截断,防 base64/压缩代码刷屏(对齐 ripgrep 的 --max-columns 500)。 */
    public static final int MAX_LINE_CHARS = 2000;

    /**
     * 把内容按 {@code cat -n} 格式加行号。
     *
     * @param content   文本内容(LF 换行)
     * @param startLine 首行的行号(1-based)
     */
    public static String addLineNumbers(String content, int startLine) {
        if (content.isEmpty()) {
            return "";
        }
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder(content.length() + lines.length * 8);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() > MAX_LINE_CHARS) {
                line = line.substring(0, MAX_LINE_CHARS) + "… [本行过长已截断]";
            }
            sb.append(startLine + i).append('\t').append(line);
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** 把任意换行(CRLF/CR/LF)统一规范化为 LF。 */
    public static String normalizeNewlines(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 统计 LF 行数(空串记 0 行)。 */
    public static int countLines(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    /**
     * 若文本超过 {@code maxChars} 则在行边界处截断并追加提示。
     */
    public static String truncate(String s, int maxChars) {
        if (s.length() <= maxChars) {
            return s;
        }
        int cut = s.lastIndexOf('\n', maxChars);
        String kept = cut > 0 ? s.substring(0, cut) : s.substring(0, maxChars);
        int omitted = countLines(s) - countLines(kept);
        return kept + "\n\n… [输出过长,已截断约 " + omitted + " 行] …";
    }

    /** 统计 {@code needle} 在 {@code haystack} 中作为字面量出现的次数。 */
    public static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
