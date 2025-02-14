package com.misu.account.feign;

import com.misu.account.dto.UserDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
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
}
