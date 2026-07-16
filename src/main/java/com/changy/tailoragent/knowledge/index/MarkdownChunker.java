package com.changy.tailoragent.knowledge.index;

import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 按 AST 块、句子、字符三级降级规则拆分直属正文。 */
@Component
public class MarkdownChunker {

    private final Parser parser = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();
    private final KnowledgeIndexProperties properties;

    public MarkdownChunker(KnowledgeIndexProperties properties) {
        this.properties = properties;
    }

    public List<IndexChunk> chunk(ParsedMarkdownDocument document) {
        List<IndexChunk> chunks = new ArrayList<>();
        String source = document.source();
        for (MarkdownSection section : document.sections()) {
            String direct = source.substring(section.directTextStart(), section.directTextEnd());
            List<Slice> slices = split(direct);
            if (slices.isEmpty()) {
                // 标题没有直属正文时仍生成标题型块，使标题和标题路径可以被召回。
                slices = List.of(new Slice(0, 0));
            }
            for (int i = 0; i < slices.size(); i++) {
                Slice slice = slices.get(i);
                int start = section.directTextStart() + slice.start();
                int end = section.directTextStart() + slice.end();
                String text = direct.substring(slice.start(), slice.end());
                chunks.add(new IndexChunk(
                        section.sectionId() + "-part-" + i,
                        section, i, start, end, text));
            }
        }
        return chunks;
    }

    List<Slice> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<BlockSlice> blocks = astBlocks(text);
        List<Slice> result = new ArrayList<>();
        Slice pending = null;

        for (BlockSlice block : blocks) {
            Slice trimmed = trim(text, new Slice(block.start(), block.end()));
            if (trimmed.length() == 0) {
                continue;
            }
            if (trimmed.length() > properties.getChunkMaxChars()) {
                if (pending != null) {
                    result.add(pending);
                    pending = null;
                }
                result.addAll(splitOversized(text, trimmed, block.code()));
                continue;
            }

            if (pending == null) {
                pending = trimmed;
                continue;
            }
            int combinedLength = trimmed.end() - pending.start();
            if (pending.length() >= properties.getChunkTargetChars()
                    || combinedLength > properties.getChunkMaxChars()) {
                result.add(pending);
                pending = trimmed;
            } else {
                pending = new Slice(pending.start(), trimmed.end());
            }
        }
        if (pending != null) {
            result.add(pending);
        }
        return result;
    }

    private List<BlockSlice> astBlocks(String text) {
        Node document = parser.parse(text);
        List<Node> nodes = new ArrayList<>();
        for (Node child = document.getFirstChild(); child != null; child = child.getNext()) {
            if (!child.getSourceSpans().isEmpty()) {
                nodes.add(child);
            }
        }
        if (nodes.isEmpty()) {
            return List.of(new BlockSlice(0, text.length(), false));
        }

        List<BlockSlice> result = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            int start = i == 0 ? 0 : minStart(node);
            int end = i + 1 < nodes.size() ? minStart(nodes.get(i + 1)) : text.length();
            result.add(new BlockSlice(start, end,
                    node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock));
        }
        return result;
    }

    private List<Slice> splitOversized(String text, Slice block, boolean code) {
        if (code) {
            return hardSplit(text, block);
        }
        String value = text.substring(block.start(), block.end());
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.CHINA);
        iterator.setText(value);
        List<Slice> sentences = new ArrayList<>();
        int begin = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; begin = end, end = iterator.next()) {
            Slice sentence = trim(text, new Slice(block.start() + begin, block.start() + end));
            if (sentence.length() > 0) {
                sentences.add(sentence);
            }
        }
        if (sentences.size() <= 1) {
            return hardSplit(text, block);
        }

        List<Slice> result = new ArrayList<>();
        Slice pending = null;
        for (Slice sentence : sentences) {
            if (sentence.length() > properties.getChunkMaxChars()) {
                if (pending != null) {
                    result.add(pending);
                    pending = null;
                }
                result.addAll(hardSplit(text, sentence));
            } else if (pending == null) {
                pending = sentence;
            } else if (pending.length() >= properties.getChunkTargetChars()
                    || sentence.end() - pending.start() > properties.getChunkMaxChars()) {
                result.add(pending);
                pending = sentence;
            } else {
                pending = new Slice(pending.start(), sentence.end());
            }
        }
        if (pending != null) {
            result.add(pending);
        }
        return result;
    }

    /** 只有句子或代码块仍超长时才硬拆，并加入配置的少量重叠。 */
    private List<Slice> hardSplit(String text, Slice input) {
        List<Slice> result = new ArrayList<>();
        int cursor = input.start();
        while (cursor < input.end()) {
            int end = Math.min(input.end(), cursor + properties.getChunkMaxChars());
            // 字符预算以 Java char 计，但不要在 surrogate pair 中间截断 emoji 等补充平面字符。
            if (end < input.end() && end > cursor
                    && Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) {
                end--;
            }
            if (end <= cursor) {
                end = Math.min(input.end(), cursor + properties.getChunkMaxChars());
            }
            result.add(new Slice(cursor, end));
            if (end >= input.end()) {
                break;
            }
            cursor = end - properties.getChunkOverlapChars();
            if (cursor > input.start() && cursor < input.end()
                    && Character.isLowSurrogate(text.charAt(cursor))
                    && Character.isHighSurrogate(text.charAt(cursor - 1))) {
                cursor++;
            }
        }
        return result;
    }

    private static int minStart(Node node) {
        return node.getSourceSpans().stream().mapToInt(SourceSpan::getInputIndex).min().orElse(0);
    }

    private static Slice trim(String text, Slice slice) {
        int start = slice.start();
        int end = slice.end();
        while (start < end && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return new Slice(start, end);
    }

    record Slice(int start, int end) {
        int length() {
            return end - start;
        }
    }

    private record BlockSlice(int start, int end, boolean code) {
    }
}
