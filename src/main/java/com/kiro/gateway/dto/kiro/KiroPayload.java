package com.kiro.gateway.dto.kiro;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

import java.util.UUID;

/**
 * Kiro API 请求载荷构建器
 * <p>
 * 构建发送到 Kiro API 的 conversationState 结构
 */
public class KiroPayload {

    private final JSONObject root = new JSONObject();
    private final JSONObject conversationState = new JSONObject();
    private final JSONArray history = new JSONArray();

    private String modelId;

    public KiroPayload(String modelId) {
        this.modelId = modelId;
        conversationState.put("conversationId", UUID.randomUUID().toString());
        conversationState.put("agentContinuationId", UUID.randomUUID().toString());
        conversationState.put("agentTaskType", "vibe");
        conversationState.put("chatTriggerType", "MANUAL");
        conversationState.put("history", history);
        root.put("conversationState", conversationState);
    }

    /**
     * 设置触发类型（MANUAL / AUTO）
     */
    public KiroPayload chatTriggerType(String type) {
        conversationState.put("chatTriggerType", type);
        return this;
    }

    /**
     * 设置 profileArn
     */
    public KiroPayload profileArn(String arn) {
        if (arn != null && !arn.isEmpty()) {
            root.put("profileArn", arn);
        }
        return this;
    }

    /**
     * 添加用户历史消息
     */
    public KiroPayload addUserHistory(String content) {
        JSONObject msg = new JSONObject();
        msg.put("userInputMessage", JSONObject.of(
                "content", content, //
                "modelId", modelId, //
                "origin", "AI_EDITOR" //
        ));
        history.add(msg);
        return this;
    }

    /**
     * 添加助手历史消息
     */
    public KiroPayload addAssistantHistory(String content) {
        JSONObject msg = new JSONObject();
        JSONObject assistantMsg = new JSONObject();
        assistantMsg.put("content", content);
        msg.put("assistantResponseMessage", assistantMsg);
        history.add(msg);
        return this;
    }

    /**
     * 添加助手历史消息（含工具调用）
     */
    public KiroPayload addAssistantHistory(String content, JSONArray toolUses) {
        JSONObject msg = new JSONObject();
        JSONObject assistantMsg = new JSONObject();
        assistantMsg.put("content", content);
        if (toolUses != null && !toolUses.isEmpty()) {
            assistantMsg.put("toolUses", toolUses);
        }
        msg.put("assistantResponseMessage", assistantMsg);
        history.add(msg);
        return this;
    }

    /**
     * 添加用户历史消息（含工具结果上下文）
     */
    public KiroPayload addUserHistoryWithContext(String content, JSONObject context) {
        JSONObject userMsg = JSONObject.of(
                "content", content, //
                "modelId", modelId, //
                "origin", "AI_EDITOR" //
        );
        if (context != null && !context.isEmpty()) {
            userMsg.put("userInputMessageContext", context);
        }
        JSONObject msg = new JSONObject();
        msg.put("userInputMessage", userMsg);
        history.add(msg);
        return this;
    }

    /**
     * 设置当前消息
     */
    public KiroPayload currentMessage(String content, JSONObject context) {
        JSONObject userMsg = JSONObject.of(
                "content", content, //
                "modelId", modelId, //
                "origin", "AI_EDITOR" //
        );
        if (context != null && !context.isEmpty()) {
            userMsg.put("userInputMessageContext", context);
        }
        JSONObject currentMsg = new JSONObject();
        currentMsg.put("userInputMessage", userMsg);
        conversationState.put("currentMessage", currentMsg);
        return this;
    }

    /**
     * 构建最终 JSON 字符串
     */
    public String toJsonString() {
        return root.toJSONString();
    }

    public String modelId() {
        return modelId;
    }
}
