package com.kiro.gateway.translator;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.kiro.gateway.dto.kiro.KiroPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Claude (Anthropic) ↔ Kiro 协议转换
 */
@Component
public class ClaudeTranslator implements RequestTranslator {

    private static final Logger log = LoggerFactory.getLogger(ClaudeTranslator.class);

    @Override
    public TranslateResult translate(JSONObject request, String modelId, boolean isThinking) {
        KiroPayload payload = new KiroPayload(modelId);
        Map<String, String> toolNameMap = new LinkedHashMap<>();
        Set<String> usedNames = new HashSet<>();

        JSONArray messages = request.getJSONArray("messages");
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("消息数组不能为空");
        }

        // 处理 system prompt
        String systemPrompt = extractSystemPrompt(request);
        if (isThinking) {
            String thinkingPrefix = buildThinkingPrefix(request);
            systemPrompt = thinkingPrefix + (systemPrompt != null ? "\n" + systemPrompt : "");
        }
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            payload.addUserHistory(systemPrompt);
            payload.addAssistantHistory("I will follow these instructions.");
        }

        // 分离当前消息和历史消息
        int currentStart = messages.size();
        while (currentStart > 0 && "user".equals(messages.getJSONObject(currentStart - 1).getString("role"))) {
            currentStart--;
        }

        boolean endsWithAssistant = currentStart == messages.size()
                && !messages.isEmpty()
                && "assistant".equals(messages.getJSONObject(messages.size() - 1).getString("role"));

        // 处理历史消息
        int historyEnd = endsWithAssistant ? messages.size() : currentStart;
        List<JSONObject> userBuffer = new ArrayList<>();

        for (int i = 0; i < historyEnd; i++) {
            JSONObject msg = messages.getJSONObject(i);
            String role = msg.getString("role");

            if ("user".equals(role)) {
                userBuffer.add(msg);
            } else if ("assistant".equals(role)) {
                if (!userBuffer.isEmpty()) {
                    String merged = mergeUserMessages(userBuffer);
                    payload.addUserHistory(merged);
                    userBuffer.clear();
                }

                ContentExtract extract = extractAssistantContent(msg.get("content"), toolNameMap, usedNames);
                payload.addAssistantHistory(extract.text, extract.toolUses);
            }
        }

        // 处理结尾孤立 user 消息
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

        // 构建当前消息
        String currentText = "continue";
        JSONArray allToolResults = new JSONArray();

        if (!endsWithAssistant) {
            List<String> textParts = new ArrayList<>();
            for (int i = currentStart; i < messages.size(); i++) {
                JSONObject msg = messages.getJSONObject(i);
                UserContentExtract uce = extractUserContent(msg.get("content"));
                if (uce.text != null && !uce.text.isEmpty()) {
                    textParts.add(uce.text);
                }
                allToolResults.addAll(uce.toolResults);
            }
            currentText = textParts.isEmpty() ? "continue" : String.join("\n", textParts);
        }

        if (!allToolResults.isEmpty()) {
            context.put("toolResults", allToolResults);
        }

        // 设置触发类型
        JSONArray requestTools = request.getJSONArray("tools");
        if (requestTools != null && !requestTools.isEmpty()) {
            JSONObject toolChoice = request.getJSONObject("tool_choice");
            if (toolChoice != null) {
                String tcType = toolChoice.getString("type");
                if ("any".equals(tcType) || "tool".equals(tcType)) {
                    payload.chatTriggerType("AUTO");
                }
            }
        }

        payload.currentMessage(currentText, context.isEmpty() ? null : context);

        return new TranslateResult(payload, toolNameMap);
    }

    /**
     * 将 Kiro 响应转换为 Claude 非流式响应
     */
    public JSONObject toClaudeResponse(String content, String thinkingContent,
                                        JSONArray toolUses,
                                        int inputTokens, int outputTokens,
                                        String model, String stopReason) {
        JSONArray contentBlocks = new JSONArray();

        // thinking block 放在 content 数组开头
        if (thinkingContent != null && !thinkingContent.isEmpty()) {
            contentBlocks.add(JSONObject.of("type", "thinking", "thinking", thinkingContent));
        }
        if (content != null && !content.isEmpty()) {
            contentBlocks.add(JSONObject.of("type", "text", "text", content));
        }
        if (toolUses != null) {
            contentBlocks.addAll(toolUses);
        }

        JSONObject result = new JSONObject();
        result.put("id", "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        result.put("type", "message");
        result.put("role", "assistant");
        result.put("model", model);
        result.put("content", contentBlocks);
        result.put("stop_reason", stopReason != null ? stopReason : "end_turn");
        result.put("stop_sequence", JSONObject.of());
        result.put("usage", JSONObject.of( //
                "input_tokens", inputTokens, //
                "output_tokens", outputTokens //
        ));
        return result;
    }

    // ==================== 辅助方法 ====================

    private String extractSystemPrompt(JSONObject request) {
        Object system = request.get("system");
        if (system == null) return null;
        if (system instanceof String s) return s;
        if (system instanceof JSONArray arr) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject item = arr.getJSONObject(i);
                if (item != null && item.containsKey("text")) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(item.getString("text"));
                }
            }
            return sb.toString();
        }
        return null;
    }

    private String buildThinkingPrefix(JSONObject request) {
        JSONObject thinking = request.getJSONObject("thinking");
        int budgetTokens = 10000;
        if (thinking != null) {
            budgetTokens = thinking.getIntValue("budget_tokens", 10000);
        }
        return "<thinking_mode>enabled</thinking_mode><max_thinking_length>" + budgetTokens + "</max_thinking_length>";
    }

    private String mergeUserMessages(List<JSONObject> messages) {
        List<String> parts = new ArrayList<>();
        for (JSONObject msg : messages) {
            UserContentExtract uce = extractUserContent(msg.get("content"));
            if (uce.text != null && !uce.text.isEmpty()) {
                parts.add(uce.text);
            }
        }
        return parts.isEmpty() ? "continue" : String.join("\n", parts);
    }

    private UserContentExtract extractUserContent(Object content) {
        if (content == null) return new UserContentExtract("", new JSONArray());
        if (content instanceof String s) return new UserContentExtract(s, new JSONArray());

        JSONArray arr = content instanceof JSONArray ja ? ja : new JSONArray();
        List<String> textParts = new ArrayList<>();
        JSONArray toolResults = new JSONArray();

        for (int i = 0; i < arr.size(); i++) {
            JSONObject block = arr.getJSONObject(i);
            if (block == null) continue;
            String type = block.getString("type");

            if ("text".equals(type)) {
                textParts.add(block.getString("text"));
            } else if ("tool_result".equals(type)) {
                String resultContent = extractTextFromContent(block.get("content"));
                toolResults.add(JSONObject.of(
                        "toolUseId", block.getString("tool_use_id"), //
                        "status", Boolean.TRUE.equals(block.getBoolean("is_error")) ? "error" : "success", //
                        "content", JSONArray.of(JSONObject.of("text", resultContent)) //
                ));
            }
        }

        return new UserContentExtract(String.join("\n", textParts), toolResults);
    }

    private ContentExtract extractAssistantContent(Object content, Map<String, String> toolNameMap, Set<String> usedNames) {
        if (content instanceof String s) return new ContentExtract(s, null);

        JSONArray arr = content instanceof JSONArray ja ? ja : new JSONArray();
        List<String> textParts = new ArrayList<>();
        JSONArray toolUses = new JSONArray();
        StringBuilder thinkingContent = new StringBuilder();

        for (int i = 0; i < arr.size(); i++) {
            JSONObject block = arr.getJSONObject(i);
            if (block == null) continue;
            String type = block.getString("type");

            if ("thinking".equals(type)) {
                thinkingContent.append(block.getString("thinking"));
            } else if ("text".equals(type)) {
                textParts.add(block.getString("text"));
            } else if ("tool_use".equals(type)) {
                String originalName = block.getString("name");
                String kiroName = getOrCreateToolName(originalName, toolNameMap, usedNames);
                toolUses.add(JSONObject.of(
                        "toolUseId", block.getString("id"), //
                        "name", kiroName, //
                        "input", normalizeJsonObject(block.get("input")) //
                ));
            }
        }

        String finalText = String.join("\n", textParts);
        if (!thinkingContent.isEmpty()) {
            String thinking = "<thinking>" + thinkingContent + "</thinking>";
            finalText = textParts.isEmpty() ? thinking : thinking + "\n\n" + finalText;
        }

        if (finalText.isEmpty() && !toolUses.isEmpty()) {
            finalText = "OK";
        }

        return new ContentExtract(finalText, toolUses.isEmpty() ? null : toolUses);
    }

    private JSONArray buildKiroTools(JSONArray tools, Map<String, String> toolNameMap, Set<String> usedNames) {
        if (tools == null || tools.isEmpty()) return null;

        JSONArray kiroTools = new JSONArray();
        for (int i = 0; i < tools.size(); i++) {
            JSONObject tool = tools.getJSONObject(i);
            String name = tool.getString("name");

            // 跳过不支持的工具
            if (name != null && (name.equalsIgnoreCase("web_search") || name.equalsIgnoreCase("websearch"))) {
                continue;
            }

            String kiroName = getOrCreateToolName(name, toolNameMap, usedNames);
            String description = tool.getString("description");
            if (description != null && description.length() > 10000) {
                description = description.substring(0, 10000);
            }

            kiroTools.add(JSONObject.of(
                    "toolSpecification", JSONObject.of( //
                            "name", kiroName, //
                            "description", description, //
                            "inputSchema", JSONObject.of("json", normalizeJsonObject(tool.get("input_schema"))) //
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

    private JSONObject normalizeJsonObject(Object value) {
        if (value == null) return new JSONObject();
        if (value instanceof JSONObject jo) return jo;
        if (value instanceof String s) {
            try { return JSONObject.parseObject(s); } catch (Exception e) { return new JSONObject(); }
        }
        return new JSONObject();
    }

    private String extractTextFromContent(Object content) {
        if (content == null) return "";
        if (content instanceof String s) return s;
        if (content instanceof JSONArray arr) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject item = arr.getJSONObject(i);
                if (item != null && item.containsKey("text")) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(item.getString("text"));
                }
            }
            return sb.toString();
        }
        return "";
    }

    private record UserContentExtract(String text, JSONArray toolResults) {}
    private record ContentExtract(String text, JSONArray toolUses) {}
}
