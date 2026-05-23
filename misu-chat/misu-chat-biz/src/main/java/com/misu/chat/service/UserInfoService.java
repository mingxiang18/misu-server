package com.misu.chat.service;

import com.misu.account.dto.UserBriefDto;

import java.util.Collection;
import java.util.Map;

public interface UserInfoService {

    /**
     * 按 userId 批量取昵称/头像，结果按 userId 字符串建索引。带进程内缓存。
     */
    Map<String, UserBriefDto> batchGet(Collection<String> userIds);
}
