package com.misu.fileServer.controller;

import com.misu.account.feign.AccountFeignClient;
import com.misu.common.domain.AjaxResult;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户测试相关Controller
 *
 * @author misu
 */
@RestController
@RequestMapping("/userTest")
public class UserController {

    @Resource
    private AccountFeignClient accountFeignClient;

    /**
     * 根据用户名获取用户信息
     */
    @GetMapping({"/getUserFromUsername"})
    @ApiOperation(value="根据用户名获取用户信息")
    public AjaxResult getUserFromUsername(@RequestParam("username") String username) {
        return AjaxResult.success(accountFeignClient.getUserFromUsername(username));
    }
}
