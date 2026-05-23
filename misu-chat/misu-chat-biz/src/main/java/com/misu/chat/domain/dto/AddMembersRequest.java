package com.misu.chat.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class AddMembersRequest {
    private List<String> userIds;
}
