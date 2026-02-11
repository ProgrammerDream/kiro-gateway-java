package com.kiro.gateway.translator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Thinking 模式解析器
 * <p>
 * 从流式文本中提取 thinking 内容和正文内容，
 * 支持 XML 标签格式和 markdown 格式
 */
public class ThinkingParser {

    private static final Logger log = LoggerFactory.getLogger(ThinkingParser.class);

    private static final Pattern THINKING_START = Pattern.compile("<thinking>", Pattern.CASE_INSENSITIVE);
    private static final Pattern THINKING_END = Pattern.compile("</thinking>", Pattern.CASE_INSENSITIVE);

    private boolean inThinking = false;
    private final StringBuilder thinkingBuffer = new StringBuilder();
    private final StringBuilder contentBuffer = new StringBuilder();
    private final StringBuilder pendingBuffer = new StringBuilder();

    /**
     * 输入流式文本片段，返回解析结果
     *
     * @param text 新的文本片段
     * @return 解析结果
     */
    public ParseResult feed(String text) {
        if (text == null || text.isEmpty()) {
            return ParseResult.EMPTY;
        }

        pendingBuffer.append(text);
        String pending = pendingBuffer.toString();

        String thinkingDelta = null;
        String contentDelta = null;

        if (inThinking) {
            // 在 thinking 块内，查找结束标签
            Matcher endMatcher = THINKING_END.matcher(pending);
            if (endMatcher.find()) {
                // 结束标签之前的部分是 thinking 内容
                String before = pending.substring(0, endMatcher.start());
                String after = pending.substring(endMatcher.end());

                if (!before.isEmpty()) {
                    thinkingBuffer.append(before);
                    thinkingDelta = before;
                }

                inThinking = false;
                pendingBuffer.setLength(0);

                // 结束标签之后的部分是正文内容
                if (!after.isEmpty()) {
                    contentBuffer.append(after);
                    contentDelta = after;
                }
            } else {
                // 未找到结束标签，检查是否有部分标签在末尾
                int safeEnd = findSafeEnd(pending, "</thinking>");
                if (safeEnd < pending.length()) {
                    // 末尾可能是部分标签，保留
                    String safe = pending.substring(0, safeEnd);
                    if (!safe.isEmpty()) {
                        thinkingBuffer.append(safe);
                        thinkingDelta = safe;
                    }
                    pendingBuffer.setLength(0);
                    pendingBuffer.append(pending.substring(safeEnd));
                } else {
                    thinkingBuffer.append(pending);
                    thinkingDelta = pending;
                    pendingBuffer.setLength(0);
                }
            }
        } else {
            // 不在 thinking 块，查找开始标签
            Matcher startMatcher = THINKING_START.matcher(pending);
            if (startMatcher.find()) {
                // 开始标签之前的部分是正文
                String before = pending.substring(0, startMatcher.start());
                String after = pending.substring(startMatcher.end());

                if (!before.isEmpty()) {
                    contentBuffer.append(before);
                    contentDelta = before;
                }

                inThinking = true;
                pendingBuffer.setLength(0);

                // 递归处理剩余部分
                if (!after.isEmpty()) {
                    ParseResult inner = feed(after);
                    if (inner.thinkingDelta() != null) {
                        thinkingDelta = inner.thinkingDelta();
                    }
                    if (inner.contentDelta() != null) {
                        contentDelta = contentDelta != null ? contentDelta + inner.contentDelta() : inner.contentDelta();
                    }
                }
            } else {
                // 检查末尾是否可能是部分 <thinking> 标签
                int safeEnd = findSafeEnd(pending, "<thinking>");
                if (safeEnd < pending.length()) {
                    String safe = pending.substring(0, safeEnd);
                    if (!safe.isEmpty()) {
                        contentBuffer.append(safe);
                        contentDelta = safe;
                    }
                    pendingBuffer.setLength(0);
                    pendingBuffer.append(pending.substring(safeEnd));
                } else {
                    contentBuffer.append(pending);
                    contentDelta = pending;
                    pendingBuffer.setLength(0);
                }
            }
        }

        return new ParseResult(thinkingDelta, contentDelta);
    }

    /**
     * 完成解析，flush 剩余内容
     */
    public ParseResult finish() {
        String remaining = pendingBuffer.toString();
        pendingBuffer.setLength(0);

        if (remaining.isEmpty()) {
            return ParseResult.EMPTY;
        }

        if (inThinking) {
            thinkingBuffer.append(remaining);
            return new ParseResult(remaining, null);
        } else {
            contentBuffer.append(remaining);
            return new ParseResult(null, remaining);
        }
    }

    /**
     * 获取全部 thinking 内容
     */
    public String getThinkingContent() {
        return thinkingBuffer.toString();
    }

    /**
     * 获取全部正文内容
     */
    public String getContentText() {
        return contentBuffer.toString();
    }

    /**
     * 重置状态
     */
    public void reset() {
        inThinking = false;
        thinkingBuffer.setLength(0);
        contentBuffer.setLength(0);
        pendingBuffer.setLength(0);
    }

    /**
     * 查找安全截断位置，避免截断可能的标签前缀
     */
    private int findSafeEnd(String text, String tag) {
        // 检查 text 末尾是否与 tag 的前缀匹配
        for (int suffixLen = Math.min(text.length(), tag.length() - 1); suffixLen >= 1; suffixLen--) {
            String suffix = text.substring(text.length() - suffixLen);
            if (tag.substring(0, suffixLen).equalsIgnoreCase(suffix)) {
                return text.length() - suffixLen;
            }
        }
        return text.length();
    }

    /**
     * 解析结果
     */
    public record ParseResult(String thinkingDelta, String contentDelta) {
        public static final ParseResult EMPTY = new ParseResult(null, null);

        public boolean hasThinking() { return thinkingDelta != null && !thinkingDelta.isEmpty(); }
        public boolean hasContent() { return contentDelta != null && !contentDelta.isEmpty(); }
        public boolean isEmpty() { return !hasThinking() && !hasContent(); }
    }
}
