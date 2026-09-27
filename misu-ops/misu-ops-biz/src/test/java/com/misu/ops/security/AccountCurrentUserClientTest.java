package com.misu.ops.security;

import com.misu.security.dto.LoginUser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountCurrentUserClientTest {

    @Test
    void legacyNullStateIsActiveWhenUserIsNotDeleted() {
        LoginUser current = user(null, null);

        assertFalse(AccountCurrentUserClient.isDisabledOrDeleted(current));
    }

    @Test
    void explicitActiveStateIsAllowed() {
        assertFalse(AccountCurrentUserClient.isDisabledOrDeleted(user("0", "0")));
        assertFalse(AccountCurrentUserClient.isDisabledOrDeleted(user("0", null)));
    }

    @Test
    void explicitDisabledOrDeletedStateIsRejected() {
        assertTrue(AccountCurrentUserClient.isDisabledOrDeleted(user("1", "0")));
        assertTrue(AccountCurrentUserClient.isDisabledOrDeleted(user("0", "2")));
    }

    private LoginUser user(String status, String delFlag) {
        LoginUser current = new LoginUser(6L, "admin", List.of("ADMIN"));
        current.setStatus(status);
        current.setDelFlag(delFlag);
        return current;
    }
}
