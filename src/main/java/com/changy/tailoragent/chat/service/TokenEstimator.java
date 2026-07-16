package com.changy.tailoragent.chat.service;

import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 上下文 token 估算器 —— 字符级粗算,零依赖、离线可用。
 * <p>
 * <b>定位</b>:仅用于「本轮新进入投影层的助手正文」这一小段 delta,以及「模型未回 usage」
 * 时的整段兜底估算。上下文占用的<b>主项</b>始终取模型返回的真实 {@code prompt_tokens},
 * 它已精确吸收截至上一轮的全部历史;本估算只补上「本轮刚生成、要到下一轮才被投影」的回复,
 * 体量小且每轮以真实用量重新锚定,故字符级误差不会累积。
 * <p>
 * <b>口径</b>:CJK(中日韩)表意字符约 1 token/字;其余文本(拉丁、空白、标点)约 4 字符/token。
 * 这是对 BPE 分词的经验近似,够用于「占比提示」。后续若需更准,可换 JTokkit 等真分词器,
 * 本类的方法签名保持不变即可平滑替换。
 */
@Component
public class TokenEstimator {

    /** 非 CJK 文本的「字符/token」经验系数 */
    private static final double CHARS_PER_TOKEN = 4.0;

    /** 估算单段文本的 token 数 */
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                cjk++;
            } else {
                other++;
            }
        }
        return cjk + (int) Math.ceil(other / CHARS_PER_TOKEN);
    }

    /**
     * 估算「系统提示词 + 投影消息序列」的总 token —— 仅在模型未回 usage 时作兜底。
     * 每条消息额外加一个小的角色/分隔开销常量,贴近真实序列化后的体量。
     */
    public int estimateMessages(String systemPrompt, List<Message> messages) {
        int total = estimate(systemPrompt);
        if (messages != null) {
            for (Message m : messages) {
                total += estimate(m.getText()) + 4; // 4 ≈ 每条消息的角色/分隔固定开销
            }
        }
        return total;
    }

    /** 是否为 CJK 表意字符(含中文、日文假名、韩文等常见区段) */
    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)    // CJK 统一表意文字
                || (cp >= 0x3040 && cp <= 0x30FF) // 日文平假名 / 片假名
                || (cp >= 0xAC00 && cp <= 0xD7A3) // 韩文音节
                || (cp >= 0x3400 && cp <= 0x4DBF) // CJK 扩展 A
                || (cp >= 0xF900 && cp <= 0xFAFF); // CJK 兼容表意文字
    }
}
