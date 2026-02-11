package com.kiro.gateway.proxy;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.kiro.gateway.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Kiro REST API 客户端
 * <p>
 * 用于管理功能：获取用量、模型列表、用户信息等
 */
@Component
public class KiroRestApi {

    private static final Logger log = LoggerFactory.getLogger(KiroRestApi.class);

    private final HttpClient httpClient;
    private final AppProperties properties;

    public KiroRestApi(HttpClient kiroHttpClient, AppProperties properties) {
        this.httpClient = kiroHttpClient;
        this.properties = properties;
    }

    /**
     * 获取账号用量限制
     */
    public UsageLimits getUsageLimits(String accessToken) {
        String url = "https://codewhisperer.us-east-1.amazonaws.com/getUsageLimits?isEmailRequired=true&origin=AI_EDITOR&resourceType=AGENTIC_REQUEST";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("x-amz-user-agent", "aws-sdk-js/1.0.0 KiroIDE")
                .header("User-Agent", "aws-sdk-js/1.0.0 KiroIDE")
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("获取用量限制失败: status={}", response.statusCode());
                return null;
            }

            JSONObject json = JSONObject.parseObject(response.body());
            return parseUsageLimits(json);
        } catch (Exception e) {
            log.error("获取用量限制异常", e);
            return null;
        }
    }

    /**
     * 获取可用模型列表
     */
    public List<String> listAvailableModels(String accessToken) {
        String url = "https://codewhisperer.us-east-1.amazonaws.com/ListAvailableModels";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("x-amz-user-agent", "aws-sdk-js/1.0.0 KiroIDE")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("获取模型列表失败: status={}", response.statusCode());
                return List.of();
            }

            JSONObject json = JSONObject.parseObject(response.body());
            JSONArray models = json.getJSONArray("models");
            if (models == null) {
                return List.of();
            }

            List<String> modelIds = new ArrayList<>();
            for (int i = 0; i < models.size(); i++) {
                JSONObject model = models.getJSONObject(i);
                String modelId = model.getString("modelId");
                if (modelId != null) {
                    modelIds.add(modelId);
                }
            }
            return modelIds;
        } catch (Exception e) {
            log.error("获取模型列表异常", e);
            return List.of();
        }
    }

    private UsageLimits parseUsageLimits(JSONObject data) {
        double usageLimit = 0;
        double currentUsage = 0;
        String userEmail = null;
        String subscriptionType = null;

        JSONObject userInfo = data.getJSONObject("userInfo");
        if (userInfo != null) {
            userEmail = userInfo.getString("email");
        }

        JSONObject subscriptionInfo = data.getJSONObject("subscriptionInfo");
        if (subscriptionInfo != null) {
            subscriptionType = subscriptionInfo.getString("type");
        }

        JSONArray breakdowns = data.getJSONArray("usageBreakdownList");
        if (breakdowns != null) {
            for (int i = 0; i < breakdowns.size(); i++) {
                JSONObject breakdown = breakdowns.getJSONObject(i);
                if ("CREDIT".equals(breakdown.getString("resourceType"))) {
                    usageLimit = breakdown.getDoubleValue("usageLimitWithPrecision");
                    if (usageLimit == 0) usageLimit = breakdown.getDoubleValue("usageLimit");
                    currentUsage = breakdown.getDoubleValue("currentUsageWithPrecision");
                    if (currentUsage == 0) currentUsage = breakdown.getDoubleValue("currentUsage");

                    // 处理免费试用
                    JSONObject freeTrial = breakdown.getJSONObject("freeTrialInfo");
                    if (freeTrial != null && "ACTIVE".equals(freeTrial.getString("freeTrialStatus"))) {
                        double ftLimit = freeTrial.getDoubleValue("usageLimitWithPrecision");
                        double ftUsed = freeTrial.getDoubleValue("currentUsageWithPrecision");
                        usageLimit += ftLimit;
                        currentUsage += ftUsed;
                    }
                    break;
                }
            }
        }

        return new UsageLimits(usageLimit, currentUsage, Math.max(0, usageLimit - currentUsage),
                userEmail, subscriptionType);
    }

    /**
     * 用量限制信息
     */
    public record UsageLimits(double usageLimit, double currentUsage, double available,
                               String userEmail, String subscriptionType) {}
}
