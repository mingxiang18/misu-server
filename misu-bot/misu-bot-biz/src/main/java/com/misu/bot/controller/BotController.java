package com.misu.bot.controller;

import com.misu.bot.service.BotService;
import com.misu.common.domain.AjaxResult;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

/**
 * bot相关Controller
 *
 * @author misu
 */
@RestController
@RequestMapping("/bot")
@Api("bot相关接口")
public class BotController {

    @Resource
    private BotService botService;

    /**
     * 获取提供客户端连接的服务端的socket的url
     */
    @GetMapping({"/getServerWebSocketUrl"})
    @ApiOperation(value="获取提供客户端连接的服务端的socket的url")
    public AjaxResult getClientWebSocketUrl() {
        return AjaxResult.success(botService.getServerWebSocketUrl());
    }

    /**
     * 获取授权token
     */
    @GetMapping({"/getBotAccessToken"})
    @ApiOperation(value="获取授权token")
    public AjaxResult getBotAccessToken() {
        return AjaxResult.success(botService.getBotAccessToken());
    }
}
