package com.kiro.gateway.translator;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.kiro.gateway.dto.kiro.KiroPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * OpenAI ↔ Kiro 协议转换
 * <p>
 * 将 OpenAI Chat Completions 请求转为 Kiro 请求，
 * 并将 Kiro 响应转为 OpenAI 格式
 */
@Component
public class OpenAiTranslator implements RequestTranslator {

    private static final Logger log = LoggerFactory.getLogger(OpenAiTranslator.class);

    @Override
    public TranslateResult translate(JSONObject request, String modelId, boolean isThinking) {
        KiroPayload payload = new KiroPayload(modelId);
        Map<String, String> toolNameMap = new LinkedHashMap<>();
        Set<String> usedNames = new HashSet<>();

        JSONArray messages = request.getJSONArray("messages");
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("消息数组不能为空");
        }

        // 处理 system 消息
        String systemPrompt = extractSystemMessages(messages);
        if (isThinking) {
            systemPrompt = "<thinking_mode>enabled</thinking_mode>" + (systemPrompt != null ? "\n" + systemPrompt : "");
        }
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            payload.addUserHistory(systemPrompt);
            payload.addAssistantHistory("I will follow these instructions.");
        }

        // 过滤非 system 消息
        List<JSONObject> nonSystemMsgs = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            JSONObject msg = messages.getJSONObject(i);
            String role = msg.getString("role");
            if (!"system".equals(role) && !"developer".equals(role)) {
                nonSystemMsgs.add(msg);
            }
        }

        if (nonSystemMsgs.isEmpty()) {
            payload.currentMessage("Hello", null);
            return new TranslateResult(payload, toolNameMap);
        }

        // 分离历史和当前消息
        int currentStart = nonSystemMsgs.size();
        while (currentStart > 0 && "user".equals(nonSystemMsgs.get(currentStart - 1).getString("role"))) {
            currentStart--;
        }

        boolean endsWithAssistant = currentStart == nonSystemMsgs.size()
                && "assistant".equals(nonSystemMsgs.get(nonSystemMsgs.size() - 1).getString("role"));

        // 处理历史消息
        int historyEnd = endsWithAssistant ? nonSystemMsgs.size() : currentStart;
        List<JSONObject> userBuffer = new ArrayList<>();

        for (int i = 0; i < historyEnd; i++) {
            JSONObject msg = nonSystemMsgs.get(i);
            String role = msg.getString("role");

            if ("user".equals(role)) {
                userBuffer.add(msg);
            } else if ("assistant".equals(role)) {
                if (!userBuffer.isEmpty()) {
                    String merged = mergeUserMessages(userBuffer);
                    payload.addUserHistory(merged);
                    userBuffer.clear();
                }
                ContentExtract extract = extractAssistantContent(msg, toolNameMap, usedNames);
                payload.addAssistantHistory(extract.text, extract.toolUses);
            } else if ("tool".equals(role)) {
                // tool 消息紧跟在 assistant 之后，作为 user context
                userBuffer.add(msg);
            }
        }

        // 处理结尾孤立的 user/tool 消息
        if (!userBuffer.isEmpty()) {
            String merged = mergeUserMessages(userBuffer);
            payload.addUserHistory(merged);
            payload.addAssistantHistory("OK");
            userBuffer.clear();
        }

        // 构建工具定义
        JSONArray tools = buildKiroTools(request.getJSONArray("tools"), toolNameMap, usedNames);
        JSONObject context = new JSONObject();
        if (tools != null && !tools.isEmpty()) {
            context.put("tools", tools);
        }

        // 当前消息
        String currentText = "continue";
        JSONArray allToolResults = new JSONArray();

        if (!endsWithAssistant) {
            List<String> textParts = new ArrayList<>();
            for (int i = currentStart; i < nonSystemMsgs.size(); i++) {
                JSONObject msg = nonSystemMsgs.get(i);
                String role = msg.getString("role");

                if ("user".equals(role)) {
                    String text = extractUserText(msg);
                    if (text != null && !text.isEmpty()) {
                        textParts.add(text);
                    }
                }
                if ("tool".equals(role)) {
                    allToolResults.add(JSONObject.of(
                            "toolUseId", msg.getString("tool_call_id"), //
                            "status", "success", //
                            "content", JSONArray.of(JSONObject.of("text", extractText(msg.get("content")))) //
                    ));
                }
            }
            currentText = textParts.isEmpty() ? "continue" : String.join("\n", textParts);
        }

        if (!allToolResults.isEmpty()) {
            context.put("toolResults", allToolResults);
        }

        payload.currentMessage(currentText, context.isEmpty() ? null : context);
        return new TranslateResult(payload, toolNameMap);
    }

    /**
     * 将 Kiro 响应转换为 OpenAI Chat Completion（非流式）
     */
    public JSONObject toOpenAiResponse(String content, String reasoningContent,
                                        JSONArray toolCalls,
                                        int inputTokens, int outputTokens,
                                        String model, String finishReason) {
        JSONObject message = JSONObject.of(
                "role", "assistant", //
                "content", content //
        );
        if (reasoningContent != null && !reasoningContent.isEmpty()) {
            message.put("reasoning_content", reasoningContent);
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            message.put("tool_calls", toolCalls);
        }

        JSONObject choice = JSONObject.of(
                "index", 0, //
                "message", message, //
                "finish_reason", finishReason != null ? finishReason : "stop" //
        );

        JSONObject result = new JSONObject();
        result.put("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        result.put("object", "chat.completion");
        result.put("created", System.currentTimeMillis() / 1000);
        result.put("model", model);
        result.put("choices", JSONArray.of(choice));
        result.put("usage", JSONObject.of( //
                "prompt_tokens", inputTokens, //
                "completion_tokens", outputTokens, //
                "total_tokens", inputTokens + outputTokens //
        ));
        return result;
    }

    /**
     * 构建 SSE chunk（流式）
     */
    public JSONObject toOpenAiStreamChunk(String model, String deltaContent,
                                           String reasoningContent,
                                           JSONObject toolCallDelta, String finishReason) {
        JSONObject delta = new JSONObject();
        if (reasoningContent != null) {
            delta.put("reasoning_content", reasoningContent);
        }
        if (deltaContent != null) {
            delta.put("content", deltaContent);
        }
        if (toolCallDelta != null) {
            delta.put("tool_calls", JSONArray.of(toolCallDelta));
        }

        JSONObject choice = JSONObject.of(
                "index", 0, //
                "delta", delta //
        );
        if (finishReason != null) {
            choice.put("finish_reason", finishReason);
        }

        return JSONObject.of(
                "id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24), //
                "object", "chat.completion.chunk", //
                "created", System.currentTimeMillis() / 1000, //
                "model", model, //
                "choices", JSONArray.of(choice) //
        );
    }

    // ==================== 辅助方法 ====================

    private String extractSystemMessages(JSONArray messages) {
        List<String> systemParts = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            JSONObject msg = messages.getJSONObject(i);
            String role = msg.getString("role");
            if ("system".equals(role) || "developer".equals(role)) {
                String text = extractText(msg.get("content"));
                if (text != null && !text.isEmpty()) {
                    systemParts.add(text);
                }
            }
        }
        return systemParts.isEmpty() ? null : String.join("\n", systemParts);
    }

    private String mergeUserMessages(List<JSONObject> messages) {
        List<String> parts = new ArrayList<>();
        for (JSONObject msg : messages) {
            String role = msg.getString("role");
            if ("tool".equals(role)) {
                String text = extractText(msg.get("content"));
                parts.add("[Tool Result: " + msg.getString("tool_call_id") + "] " + text);
                continue;
            }
            String text = extractUserText(msg);
            if (text != null && !text.isEmpty()) {
                parts.add(text);
            }
        }
        return parts.isEmpty() ? "continue" : String.join("\n", parts);
    }

    private String extractUserText(JSONObject msg) {
        Object content = msg.get("content");
        if (content == null) return null;
        if (content instanceof String s) return s;
        if (content instanceof JSONArray arr) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject block = arr.getJSONObject(i);
                if (block == null) continue;
                String type = block.getString("type");
                if ("text".equals(type)) {
                    parts.add(block.getString("text"));
                } else if ("image_url".equals(type)) {
                    JSONObject imageUrl = block.getJSONObject("image_url");
                    if (imageUrl != null) {
                        parts.add("[image: " + imageUrl.getString("url") + "]");
                    }
                }
            }
            return String.join("\n", parts);
        }
        return content.toString();
    }

    private ContentExtract extractAssistantContent(JSONObject msg, Map<String, String> toolNameMap, Set<String> usedNames) {
        String text = extractText(msg.get("content"));
        JSONArray toolCalls = msg.getJSONArray("tool_calls");
        JSONArray kiroToolUses = null;

        if (toolCalls != null && !toolCalls.isEmpty()) {
            kiroToolUses = new JSONArray();
            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject tc = toolCalls.getJSONObject(i);
                JSONObject function = tc.getJSONObject("function");
                String name = function.getString("name");
                String kiroName = getOrCreateToolName(name, toolNameMap, usedNames);

                JSONObject inputJson;
                try {
                    inputJson = JSONObject.parseObject(function.getString("arguments"));
                } catch (Exception e) {
                    inputJson = JSONObject.of("raw", function.getString("arguments"));
                }

                kiroToolUses.add(JSONObject.of(
                        "toolUseId", tc.getString("id"), //
                        "name", kiroName, //
                        "input", inputJson //
                ));
            }
        }

        if ((text == null || text.isEmpty()) && kiroToolUses != null) {
            text = "OK";
        }

        return new ContentExtract(text, kiroToolUses);
    }

    private JSONArray buildKiroTools(JSONArray tools, Map<String, String> toolNameMap, Set<String> usedNames) {
        if (tools == null || tools.isEmpty()) return null;

        JSONArray kiroTools = new JSONArray();
        for (int i = 0; i < tools.size(); i++) {
            JSONObject tool = tools.getJSONObject(i);
            if (!"function".equals(tool.getString("type"))) continue;

            JSONObject function = tool.getJSONObject("function");
            String name = function.getString("name");
            String kiroName = getOrCreateToolName(name, toolNameMap, usedNames);
            String description = function.getString("description");
            if (description != null && description.length() > 10000) {
                description = description.substring(0, 10000);
            }

            JSONObject parameters = function.getJSONObject("parameters");
            kiroTools.add(JSONObject.of(
                    "toolSpecification", JSONObject.of( //
                            "name", kiroName, //
                            "description", description, //
                            "inputSchema", JSONObject.of("json", parameters != null ? parameters : new JSONObject()) //
                    ) //
            ));
        }
        return kiroTools;
    }

    private String getOrCreateToolName(String originalName, Map<String, String> toolNameMap, Set<String> usedNames) {
        if (toolNameMap.containsKey(originalName)) return toolNameMap.get(originalName);
        String safe = sanitizeToolName(originalName);
        String candidate = safe;
        int i = 2;
        while (usedNames.contains(candidate)) {
            candidate = safe + "_" + i++;
        }
        usedNames.add(candidate);
        toolNameMap.put(originalName, candidate);
        return candidate;
    }

    private String sanitizeToolName(String name) {
        if (name == null || name.isEmpty()) return "tool";
        String replaced = name.replaceAll("[^a-zA-Z0-9_]", "_").replaceAll("_+", "_");
        String trimmed = replaced.replaceAll("^_+|_+$", "");
        if (trimmed.isEmpty()) return "tool";
        return trimmed.matches("^[0-9].*") ? "t_" + trimmed : trimmed;
    }

    private String extractText(Object content) {
        if (content == null) return "";
        if (content instanceof String s) return s;
        if (content instanceof JSONArray arr) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject block = arr.getJSONObject(i);
                if (block != null && block.containsKey("text")) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(block.getString("text"));
                }
            }
            return sb.toString();
        }
        return content.toString();
    }

    private record ContentExtract(String text, JSONArray toolUses) {}
}
