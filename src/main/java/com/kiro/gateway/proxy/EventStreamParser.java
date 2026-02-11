package com.kiro.gateway.proxy;

import com.alibaba.fastjson2.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * AWS Event Stream 二进制解析器
 * <p>
 * 解析 Kiro API 返回的二进制事件流格式：
 * - 每个消息包含 prelude(12字节) + headers + payload + CRC(4字节)
 * - 支持嵌套事件和 gzip 压缩 payload
 */
public class EventStreamParser {

    private static final Logger log = LoggerFactory.getLogger(EventStreamParser.class);

    private static final int PRELUDE_SIZE = 12;
    private static final int MESSAGE_CRC_SIZE = 4;
    private static final int MIN_MESSAGE_SIZE = PRELUDE_SIZE + MESSAGE_CRC_SIZE;

    private final StreamCallback callback;
    private ByteBuffer buffer = ByteBuffer.allocate(0);

    // 工具调用缓冲
    private String currentToolUseId;
    private StringBuilder toolInputBuffer;

    public EventStreamParser(StreamCallback callback) {
        this.callback = callback;
    }

    /**
     * 向缓冲区添加数据
     */
    public void feed(byte[] data) {
        // 合并旧缓冲区和新数据
        byte[] combined = new byte[buffer.remaining() + data.length];
        buffer.get(combined, 0, buffer.remaining());
        System.arraycopy(data, 0, combined, combined.length - data.length, data.length);
        buffer = ByteBuffer.wrap(combined);

        // 解析所有完整的帧
        parseFrames();
    }

    /**
     * 解析流结束时的最终处理
     */
    public void finish() {
        // 完成未结束的工具调用
        finishToolUse();
        callback.onComplete();
    }

    private void parseFrames() {
        while (buffer.remaining() >= MIN_MESSAGE_SIZE) {
            int startPos = buffer.position();

            // 读取 prelude
            int totalLength = buffer.getInt();
            int headersLength = buffer.getInt();
            // 跳过 prelude CRC
            buffer.getInt();

            // 验证长度
            if (totalLength < MIN_MESSAGE_SIZE || totalLength > 16 * 1024 * 1024) {
                // 无效帧，跳过一个字节重试
                buffer.position(startPos + 1);
                continue;
            }

            // 检查是否有足够数据
            if (startPos + totalLength > buffer.limit()) {
                // 数据不足，回退等待更多数据
                buffer.position(startPos);
                break;
            }

            // 解析 headers
            byte[] headersBytes = new byte[headersLength];
            buffer.get(headersBytes);
            java.util.Map<String, String> headers = parseHeaders(headersBytes);

            // 解析 payload
            int payloadLength = totalLength - PRELUDE_SIZE - headersLength - MESSAGE_CRC_SIZE;
            byte[] payload = new byte[payloadLength];
            buffer.get(payload);

            // 跳过 message CRC
            buffer.getInt();

            // 检查嵌套事件流
            String contentType = headers.get(":content-type");
            if ("application/vnd.amazon.eventstream".equals(contentType) && payload.length > 0) {
                // 嵌套事件流，递归解析 payload
                ByteBuffer nested = ByteBuffer.wrap(payload);
                parseNestedFrame(nested);
                continue;
            }

            // 尝试解压 gzip
            payload = tryDecompress(payload);

            // 处理事件
            String eventType = headers.get(":event-type");
            if (eventType != null && payload.length > 0) {
                handleEvent(eventType, new String(payload, StandardCharsets.UTF_8));
            }
        }

        // 压缩剩余数据
        if (!buffer.hasRemaining()) {
            buffer = ByteBuffer.allocate(0);
            return;
        }
        byte[] remaining = new byte[buffer.remaining()];
        buffer.get(remaining);
        buffer = ByteBuffer.wrap(remaining);
    }

    private void parseNestedFrame(ByteBuffer nested) {
        if (nested.remaining() < MIN_MESSAGE_SIZE) {
            return;
        }
        int totalLength = nested.getInt();
        int headersLength = nested.getInt();
        nested.getInt(); // prelude CRC

        if (totalLength < MIN_MESSAGE_SIZE || totalLength > nested.limit() + PRELUDE_SIZE) {
            return;
        }

        byte[] headersBytes = new byte[headersLength];
        nested.get(headersBytes);
        java.util.Map<String, String> headers = parseHeaders(headersBytes);

        int payloadLength = totalLength - PRELUDE_SIZE - headersLength - MESSAGE_CRC_SIZE;
        byte[] payload = new byte[payloadLength];
        nested.get(payload);

        payload = tryDecompress(payload);

        String eventType = headers.get(":event-type");
        if (eventType != null && payload.length > 0) {
            handleEvent(eventType, new String(payload, StandardCharsets.UTF_8));
        }
    }

    private java.util.Map<String, String> parseHeaders(byte[] data) {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
        int offset = 0;

        while (offset < data.length) {
            // header name 长度
            int nameLength = data[offset] & 0xFF;
            offset++;

            // header name
            String name = new String(data, offset, nameLength, StandardCharsets.UTF_8);
            offset += nameLength;

            // header type
            int headerType = data[offset] & 0xFF;
            offset++;

            // 根据类型读取值
            switch (headerType) {
                case 7 -> {
                    // string
                    int strLen = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
                    offset += 2;
                    headers.put(name, new String(data, offset, strLen, StandardCharsets.UTF_8));
                    offset += strLen;
                }
                case 6 -> {
                    // bytes
                    int bytesLen = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
                    offset += 2;
                    offset += bytesLen;
                }
                case 0 -> headers.put(name, "true");
                case 1 -> headers.put(name, "false");
                case 2 -> { offset++; }
                case 3 -> { offset += 2; }
                case 4 -> { offset += 4; }
                case 5 -> { offset += 8; }
                case 8 -> { offset += 8; }
                case 9 -> { offset += 16; }
                default -> {
                    // 未知类型，停止解析
                    return headers;
                }
            }
        }
        return headers;
    }

    private void handleEvent(String eventType, String payload) {
        try {
            JSONObject json = JSONObject.parseObject(payload);
            if (json == null) {
                return;
            }

            switch (eventType) {
                case "assistantResponseEvent" -> handleAssistantResponse(json);
                case "reasoningContentEvent" -> handleReasoningContent(json);
                case "toolUseEvent" -> handleToolUseEvent(json);
                case "messageMetadataEvent" -> handleMessageMetadata(json);
                case "meteringEvent" -> handleMeteringEvent(json);
                default -> log.debug("未知事件类型: {}", eventType);
            }
        } catch (Exception e) {
            log.warn("解析事件失败: type={}, error={}", eventType, e.getMessage());
        }
    }

    private void handleAssistantResponse(JSONObject json) {
        String content = json.getString("content");
        if (content != null && !content.isEmpty()) {
            callback.onText(content);
        }
    }

    private void handleReasoningContent(JSONObject json) {
        String content = json.getString("content");
        if (content != null && !content.isEmpty()) {
            callback.onThinking(content);
        }
    }

    private void handleToolUseEvent(JSONObject json) {
        String toolUseId = json.getString("toolUseId");
        String name = json.getString("name");
        String input = json.getString("input");

        if (toolUseId != null && name != null) {
            // 新工具调用开始
            finishToolUse();
            currentToolUseId = toolUseId;
            toolInputBuffer = new StringBuilder();
            callback.onToolUseStart(toolUseId, name);
        }

        if (input != null && currentToolUseId != null) {
            toolInputBuffer.append(input);
            callback.onToolUseInput(currentToolUseId, input);
        }
    }

    private void handleMessageMetadata(JSONObject json) {
        JSONObject usage = json.getJSONObject("usage");
        if (usage != null) {
            int inputTokens = usage.getIntValue("inputTokens", 0);
            int outputTokens = usage.getIntValue("outputTokens", 0);
            callback.onUsage(inputTokens, outputTokens);
        }
    }

    private void handleMeteringEvent(JSONObject json) {
        double credits = json.getDoubleValue("credits");
        if (credits > 0) {
            callback.onCredits(credits);
        }
    }

    private void finishToolUse() {
        if (currentToolUseId != null) {
            callback.onToolUseEnd(currentToolUseId);
            currentToolUseId = null;
            toolInputBuffer = null;
        }
    }

    private byte[] tryDecompress(byte[] data) {
        if (data.length < 2) {
            return data;
        }
        // 检查 gzip 魔数 0x1f 0x8b
        if ((data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream gis = new GZIPInputStream(new java.io.ByteArrayInputStream(data));
                 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int len;
                while ((len = gis.read(buf)) != -1) {
                    bos.write(buf, 0, len);
                }
                return bos.toByteArray();
            } catch (IOException e) {
                // 解压失败，使用原始数据
            }
        }
        return data;
    }
}
