package com.misu.ops.ai;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.misu.ops.controller.OpsController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiCliToolTest {

    @Test
    void mapsOnlyFixedProfilesToExecutableNames() {
        assertEquals("codex", AiCliTool.parse("codex").command());
        assertEquals("claude", AiCliTool.parse("CLAUDE").command());
    }

    @Test
    void buildsOnlyTheExactRemoteWrapperCommands() {
        assertEquals("/usr/local/bin/misu-ai-cli codex",
                AiCliConnectionService.commandFor(AiCliTool.CODEX));
        assertEquals("/usr/local/bin/misu-ai-cli claude",
                AiCliConnectionService.commandFor(AiCliTool.CLAUDE));
    }

    @Test
    void rejectsUnknownOrNullProfiles() {
        ServiceException unknown = assertThrows(ServiceException.class, () -> AiCliTool.parse("sh -c id"));
        assertEquals(HttpStatus.BAD_REQUEST, unknown.getCode());
        assertThrows(ServiceException.class, () -> AiCliTool.parse(null));
    }

    @Test
    void requestRejectsUnrecognizedCommandFields() {
        assertThrows(Exception.class, () -> new ObjectMapper().readValue(
                "{\"tool\":\"CODEX\",\"command\":\"id\"}",
                OpsController.AiSessionRequest.class));
    }
}
