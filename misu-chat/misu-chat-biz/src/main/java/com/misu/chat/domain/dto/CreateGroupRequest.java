package com.misu.chat.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class CreateGroupRequest {
    private String title;
    private List<String> memberUserIds;
}
