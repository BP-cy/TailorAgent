package com.changy.tailoragent.knowledge.index;

import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.Node;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;

/** 使用 CommonMark AST 构建标题父子树，并保留所有原文字符偏移。 */
@Component
public class MarkdownSectionParser {

    private final Parser parser = Parser.builder()
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();
    private final KnowledgeIndexProperties properties;

    public MarkdownSectionParser(KnowledgeIndexProperties properties) {
        this.properties = properties;
    }

    public ParsedMarkdownDocument parse(String docPath, String source) {
        String safeSource = source == null ? "" : source;
        Node document = parser.parse(safeSource);
        List<HeadingToken> headings = collectHeadings(document, safeSource);

        if (headings.isEmpty()) {
            MarkdownSection section = syntheticSection(docPath, safeSource, 0, safeSource.length(), 0);
            return new ParsedMarkdownDocument(docPath, safeSource, List.of(section), List.of(section));
        }

        List<MutableSection> flat = new ArrayList<>();
        List<MutableSection> roots = new ArrayList<>();
        int ordinal = 0;

        // 第一个标题前的正文不是虚拟根本身，而是独立的普通无标题 Section。
        int firstHeadingStart = headings.getFirst().start();
        if (!safeSource.substring(0, firstHeadingStart).isBlank()) {
            MutableSection preamble = MutableSection.synthetic(
                    docPath, fileName(docPath), 0, ordinal++, 0, firstHeadingStart,
                    properties.getSectionMaxReturnChars());
            roots.add(preamble);
            flat.add(preamble);
        }

        Deque<MutableSection> stack = new ArrayDeque<>();
        for (int i = 0; i < headings.size(); i++) {
            HeadingToken token = headings.get(i);
            while (!stack.isEmpty() && stack.peekLast().level >= token.level()) {
                stack.removeLast();
            }
            MutableSection parent = stack.peekLast();
            int directEnd = i + 1 < headings.size() ? headings.get(i + 1).start() : safeSource.length();
            int sectionEnd = safeSource.length();
            for (int j = i + 1; j < headings.size(); j++) {
                HeadingToken next = headings.get(j);
                if (next.level() <= token.level()) {
                    sectionEnd = next.start();
                    break;
                }
            }

            String headingPath = parent == null || parent.headingPath.isBlank()
                    ? token.heading()
                    : parent.headingPath + " > " + token.heading();
            MutableSection current = new MutableSection(
                    sectionId(docPath, ordinal, token.level()), docPath,
                    parent, token.level(), ordinal++, token.heading(), headingPath,
                    token.start(), sectionEnd, token.contentEnd(), directEnd,
                    sectionEnd - token.start(),
                    sectionEnd - token.start() <= properties.getSectionMaxReturnChars());
            if (parent == null) {
                roots.add(current);
            } else {
                parent.children.add(current);
            }
            flat.add(current);
            stack.addLast(current);
        }

        List<MarkdownSection> rootRecords = roots.stream().map(MutableSection::toRecord).toList();
        List<MarkdownSection> flatRecords = new ArrayList<>();
        for (MarkdownSection root : rootRecords) {
            flatten(root, flatRecords);
        }
        return new ParsedMarkdownDocument(docPath, safeSource, rootRecords, flatRecords);
    }

    private static List<HeadingToken> collectHeadings(Node document, String source) {
        List<HeadingToken> headings = new ArrayList<>();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Heading heading) {
                List<SourceSpan> spans = heading.getSourceSpans();
                if (!spans.isEmpty()) {
                    int start = spans.stream().mapToInt(SourceSpan::getInputIndex).min().orElse(0);
                    int rawEnd = spans.stream()
                            .mapToInt(span -> span.getInputIndex() + span.getLength())
                            .max().orElse(start);
                    int contentEnd = advancePastLineEnding(source, rawEnd);
                    headings.add(new HeadingToken(
                            heading.getLevel(), start, contentEnd, headingText(heading)));
                }
            }
        });
        headings.sort(java.util.Comparator.comparingInt(HeadingToken::start));
        return headings;
    }

    private static String headingText(Heading heading) {
        StringBuilder text = new StringBuilder();
        heading.accept(new AbstractVisitor() {
            @Override
            public void visit(Text node) {
                text.append(node.getLiteral());
            }

            @Override
            public void visit(Code node) {
                text.append(node.getLiteral());
            }

            @Override
            public void visit(SoftLineBreak node) {
                text.append(' ');
            }

            @Override
            public void visit(HardLineBreak node) {
                text.append(' ');
            }
        });
        return text.toString().strip();
    }

    private static int advancePastLineEnding(String source, int offset) {
        int cursor = Math.max(0, Math.min(offset, source.length()));
        while (cursor < source.length() && source.charAt(cursor) != '\n' && source.charAt(cursor) != '\r') {
            cursor++;
        }
        if (cursor < source.length() && source.charAt(cursor) == '\r') {
            cursor++;
            if (cursor < source.length() && source.charAt(cursor) == '\n') {
                cursor++;
            }
        } else if (cursor < source.length() && source.charAt(cursor) == '\n') {
            cursor++;
        }
        return cursor;
    }

    private MarkdownSection syntheticSection(String docPath, String source, int start, int end, int ordinal) {
        return MutableSection.synthetic(docPath, fileName(docPath), 0, ordinal, start, end,
                properties.getSectionMaxReturnChars()).toRecord();
    }

    private static void flatten(MarkdownSection section, List<MarkdownSection> target) {
        target.add(section);
        for (MarkdownSection child : section.children()) {
            flatten(child, target);
        }
    }

    private static String sectionId(String docPath, int ordinal, int level) {
        return sha256(docPath + "\n" + ordinal + "\n" + level);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法生成 Markdown Section ID", e);
        }
    }

    private static String fileName(String docPath) {
        String normalized = docPath == null ? "" : docPath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private record HeadingToken(int level, int start, int contentEnd, String heading) {
    }

    private static final class MutableSection {
        private final String sectionId;
        private final String docPath;
        private final MutableSection parent;
        private final int level;
        private final int ordinal;
        private final String heading;
        private final String headingPath;
        private final int sectionStart;
        private final int sectionEnd;
        private final int directTextStart;
        private final int directTextEnd;
        private final int sectionCharCount;
        private final boolean expandable;
        private final List<MutableSection> children = new ArrayList<>();

        private MutableSection(String sectionId, String docPath, MutableSection parent,
                               int level, int ordinal, String heading, String headingPath,
                               int sectionStart, int sectionEnd, int directTextStart,
                               int directTextEnd, int sectionCharCount, boolean expandable) {
            this.sectionId = sectionId;
            this.docPath = docPath;
            this.parent = parent;
            this.level = level;
            this.ordinal = ordinal;
            this.heading = heading;
            this.headingPath = headingPath;
            this.sectionStart = sectionStart;
            this.sectionEnd = sectionEnd;
            this.directTextStart = directTextStart;
            this.directTextEnd = directTextEnd;
            this.sectionCharCount = sectionCharCount;
            this.expandable = expandable;
        }

        private static MutableSection synthetic(String docPath, String headingPath, int level, int ordinal,
                                                int start, int end, int maxReturnChars) {
            return new MutableSection(sectionId(docPath, ordinal, level), docPath, null,
                    level, ordinal, "", headingPath, start, end, start, end,
                    end - start, end - start <= maxReturnChars);
        }

        private MarkdownSection toRecord() {
            List<String> ancestors = new ArrayList<>();
            for (MutableSection cursor = parent; cursor != null; cursor = cursor.parent) {
                ancestors.add(cursor.sectionId);
            }
            return new MarkdownSection(sectionId, docPath,
                    parent == null ? null : parent.sectionId,
                    ancestors, level, ordinal, heading, headingPath,
                    sectionStart, sectionEnd, directTextStart, directTextEnd,
                    sectionCharCount, expandable,
                    children.stream().map(MutableSection::toRecord).toList());
        }
    }
}
