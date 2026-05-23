package com.misu.chat.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.misu.chat.config.BbConnectionConfig;
import com.misu.chat.domain.dto.UsageDto;
import com.misu.chat.domain.dto.UsageModelDto;
import com.misu.chat.service.BbUsageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 调 bb-bot 的 REST 用量接口 GET /api/ai/usage/{userId}?month= ，头 X-Api-Token。
 * 用 JDK 自带 HttpClient——它默认不使用任何代理（满足"调内网服务不走代理"）。
 * 任何异常/未配置 → available=false，前端走降级态，不影响聊天。
 */
@Slf4j
@Service
public class BbUsageServiceImpl implements BbUsageService {

    @Resource
    private BbConnectionConfig bbConnectionConfig;

    /** 默认不设 proxy(ProxySelector) → 直连，不走系统/JVM 代理。 */
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    @Override
    public UsageDto queryUsage(String userId, String month) {
        UsageDto dto = new UsageDto();
        dto.setMonth(month);

        String base = bbConnectionConfig.getHttpUrl();
        String token = bbConnectionConfig.getUsageApiToken();
        if (!StringUtils.hasText(base) || !StringUtils.hasText(token) || !StringUtils.hasText(userId)) {
            log.debug("bb 用量查询未配置或缺 userId，跳过（available=false）");
            return dto;
        }

        try {
            StringBuilder url = new StringBuilder(trimTrailingSlash(base))
                    .append("/api/ai/usage/")
                    .append(URLEncoder.encode(userId, StandardCharsets.UTF_8));
            if (StringUtils.hasText(month)) {
                url.append("?month=").append(URLEncoder.encode(month, StandardCharsets.UTF_8));
            }
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url.toString()))
                    .timeout(Duration.ofSeconds(4))
                    .header("X-Api-Token", token)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                log.warn("bb 用量查询返回非 200：{} body={}", resp.statusCode(), abbreviate(resp.body()));
                return dto;
            }
            return parse(resp.body(), month);
        } catch (Exception e) {
            log.warn("bb 用量查询失败：{}", e.getMessage());
            return dto;
        }
    }

    private UsageDto parse(String body, String fallbackMonth) {
        UsageDto dto = new UsageDto();
        JSONObject obj = JSON.parseObject(body);
        dto.setMonth(obj.getString("month") != null ? obj.getString("month") : fallbackMonth);
        dto.setSpentCny(nz(obj.getBigDecimal("spentCny")));
        dto.setLimitCny(nz(obj.getBigDecimal("limitCny")));
        dto.setRemainingCny(nz(obj.getBigDecimal("remainingCny")));
        long total = 0L;
        JSONArray models = obj.getJSONArray("models");
        if (models != null) {
            for (int i = 0; i < models.size(); i++) {
                JSONObject m = models.getJSONObject(i);
                UsageModelDto md = new UsageModelDto();
                md.setModel(m.getString("model"));
                Long tokens = m.getLong("sumTotalTokens");
                md.setTokens(tokens == null ? 0L : tokens);
                md.setCostCny(nz(m.getBigDecimal("sumCostCny")));
                total += md.getTokens();
                dto.getModels().add(md);
            }
        }
        dto.setTotalTokens(total);
        dto.setAvailable(true);
        return dto;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
