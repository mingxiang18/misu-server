package com.misu.chat.service.impl;

import com.misu.account.dto.UserBriefDto;
import com.misu.account.feign.AccountFeignClient;
import com.misu.chat.service.UserInfoService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 用户信息（昵称/头像）查询，群广播 / 历史回填高频，加进程内缓存避免每条打 account。
 */
@Slf4j
@Service
public class UserInfoServiceImpl implements UserInfoService {

    @Resource
    private AccountFeignClient accountFeignClient;

    private final Map<String, UserBriefDto> cache = new ConcurrentHashMap<>();

    @Override
    public Map<String, UserBriefDto> batchGet(Collection<String> userIds) {
        Map<String, UserBriefDto> result = new HashMap<>();
        if (CollectionUtils.isEmpty(userIds)) {
            return result;
        }

        List<Long> missing = new ArrayList<>();
        for (String idStr : userIds) {
            if (idStr == null) {
                continue;
            }
            UserBriefDto cached = cache.get(idStr);
            if (cached != null) {
                result.put(idStr, cached);
            } else {
                try {
                    missing.add(Long.valueOf(idStr));
                } catch (NumberFormatException ignored) {
                    // 非数字 userId（如 bot）跳过，由前端按 botProfile 渲染
                }
            }
        }

        if (!missing.isEmpty()) {
            try {
                List<UserBriefDto> fetched = accountFeignClient.listByIds(missing);
                if (fetched != null) {
                    for (UserBriefDto dto : fetched) {
                        String key = String.valueOf(dto.getUserId());
                        cache.put(key, dto);
                        result.put(key, dto);
                    }
                }
            } catch (Exception e) {
                // account 暂不可达时降级：返回已命中的部分，前端用 userId 兜底展示
                log.warn("批量查询用户信息失败，降级返回缓存命中部分: {}", e.getMessage());
            }
        }
        return result;
    }
}
