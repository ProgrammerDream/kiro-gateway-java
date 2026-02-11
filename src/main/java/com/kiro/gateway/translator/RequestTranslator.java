package com.kiro.gateway.translator;

import com.alibaba.fastjson2.JSONObject;
import com.kiro.gateway.dto.kiro.KiroPayload;

/**
 * 请求转换接口
 * <p>
 * 将 OpenAI/Anthropic 请求转换为 Kiro 请求
 */
public interface RequestTranslator {

    /**
     * 转换请求
     *
     * @param request    原始请求体 JSON
     * @param modelId    解析后的 Kiro 模型 ID
     * @param isThinking 是否启用 thinking 模式
     * @return 转换结果
     */
    TranslateResult translate(JSONObject request, String modelId, boolean isThinking);

    /**
     * 转换结果
     *
     * @param payload     Kiro 请求载荷
     * @param toolNameMap 工具名称映射（原始名 → Kiro 名）
     */
    record TranslateResult(KiroPayload payload, java.util.Map<String, String> toolNameMap) {}
}
