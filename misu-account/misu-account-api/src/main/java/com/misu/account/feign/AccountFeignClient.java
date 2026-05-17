package com.misu.account.feign;

import com.misu.account.dto.UserDto;
import com.misu.account.dto.VerifyCredentialsRequestDto;
import com.misu.account.dto.VerifyCredentialsResponseDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 用户相关远程调用
 *
 * @author misu
 */
@FeignClient(name = "misu-account")
public interface AccountFeignClient {

    @GetMapping("/account/inner/user/getUserFromUsername")
    UserDto getUserFromUsername(@RequestParam("username") String request);

    @PostMapping("/account/inner/user/verifyCredentials")
    VerifyCredentialsResponseDto verifyCredentials(@RequestBody VerifyCredentialsRequestDto request);
}
