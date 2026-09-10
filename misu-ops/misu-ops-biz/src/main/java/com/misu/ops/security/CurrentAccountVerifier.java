package com.misu.ops.security;

import com.misu.security.dto.LoginUser;

public interface CurrentAccountVerifier {

    LoginUser requireAdmin(LoginUser tokenUser);

    LoginUser requireAdmin(Long userId, String userName);
}
