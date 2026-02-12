package com.kiro.gateway.proxy;

/**
 * Kiro API 流式回调接口
 * <p>
 * 处理从 Kiro API Event Stream 解析出的各类事件
 */
public interface StreamCallback {

    /**
     * 收到文本内容
     */
    void onText(String text);

    /**
     * 收到 thinking/reasoning 内容
     */
    void onThinking(String thinking);

    /**
     * 收到工具调用开始
     */
    void onToolUseStart(String toolUseId, String name);

    /**
     * 收到工具调用输入片段
     */
    void onToolUseInput(String toolUseId, String inputDelta);

    /**
     * 工具调用结束
     */
    void onToolUseEnd(String toolUseId);

    /**
     * 收到 Token 使用统计
     */
    void onUsage(int inputTokens, int outputTokens);

    /**
     * 收到 Credits 消耗
     */
    void onCredits(double credits);

    /**
     * 收到上下文使用百分比
     */
    void onContextUsage(double percentage);

    /**
     * 流式完成
     */
    void onComplete();

    /**
     * 流式错误
     */
    void onError(String error);
}
