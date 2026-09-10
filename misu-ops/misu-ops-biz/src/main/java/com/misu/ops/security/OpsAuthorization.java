package com.misu.ops.security;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.security.dto.LoginUser;
import com.misu.security.utils.LoginMessageUtil;
import org.springframework.stereotype.Component;

@Component
public class OpsAuthorization {

    private final CurrentAccountVerifier currentAccountVerifier;

    public OpsAuthorization(CurrentAccountVerifier currentAccountVerifier) {
        this.currentAccountVerifier = currentAccountVerifier;
    }

    public LoginUser requireCurrentAdmin() {
        LoginUser tokenUser = LoginMessageUtil.getLoginUser()
                .orElseThrow(() -> new ServiceException(HttpStatus.UNAUTHORIZED, "未登录或登录状态已过期"));
        return currentAccountVerifier.requireAdmin(tokenUser);
    }
}
