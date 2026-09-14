package com.misu.ops.ai;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;

/** Fixed AI CLI profiles. Browser input can interact with the process, but cannot choose argv. */
public enum AiCliTool {
    CODEX("codex"),
    CLAUDE("claude");

    private final String command;

    AiCliTool(String command) {
        this.command = command;
    }

    public String command() {
        return command;
    }

    public static AiCliTool parse(String value) {
        if (value == null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "AI 工具无效");
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "AI 工具无效");
        }
    }
}
