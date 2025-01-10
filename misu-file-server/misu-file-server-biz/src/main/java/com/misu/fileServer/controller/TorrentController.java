package com.misu.fileServer.controller;

import com.misu.common.domain.AjaxResult;
import com.misu.fileServer.domain.dto.*;
import com.misu.fileServer.service.TorrentService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 磁力文件相关Controller
 *
 * @author misu
 */
@Validated
@RestController
@RequestMapping("/torrent")
@Api("磁力文件相关接口")
public class TorrentController {

    @Resource
    private TorrentService torrentService;

    /**
     * 获取磁力链接列表
     */
    @GetMapping({"/getTorrentList"})
    @ApiOperation(value="获取磁力链接列表")
    public AjaxResult getTorrentList(@Valid UserTorrentQueryRequestDto userTorrentQueryRequestDto) {
        return AjaxResult.success(torrentService.getTorrentList(userTorrentQueryRequestDto));
    }

    /**
     * 获取rss订阅列表
     */
    @GetMapping({"/getRssList"})
    @ApiOperation(value="获取rss订阅列表")
    public AjaxResult getRssList(@Valid RssQueryRequestDto rssQueryRequestDto) {
        return AjaxResult.success(torrentService.getRssList(rssQueryRequestDto));
    }

    /**
     * 获取指定rss订阅详情
     */
    @GetMapping({"/getRssDetail"})
    @ApiOperation(value="获取指定rss订阅详情")
    public AjaxResult getRssDetail(@Valid RssDetailRequestDto rssDetailRequestDto) {
        return AjaxResult.success(torrentService.getRssDetail(rssDetailRequestDto));
    }

    /**
     * 添加磁力链接
     */
    @PostMapping({"/addUserTorrent"})
    @ApiOperation(value="添加磁力链接")
    public AjaxResult addUserTorrent(@Valid @RequestBody AddTorrentRequestDto addTorrentRequestDto) {
        torrentService.addUserTorrent(addTorrentRequestDto);
        return AjaxResult.success();
    }

    /**
     * 更新磁力链接
     */
    @PostMapping({"/updateUserTorrent"})
    @ApiOperation(value="更新磁力链接")
    public AjaxResult updateUserTorrent(@Valid @RequestBody UpdateTorrentRequestDto updateTorrentRequestDto) {
        torrentService.updateUserTorrent(updateTorrentRequestDto);
        return AjaxResult.success();
    }

    /**
     * 移除磁力链接
     */
    @PostMapping({"/removeUserTorrent"})
    @ApiOperation(value="移除磁力链接")
    public AjaxResult removeUserTorrent(@Valid @RequestBody DeleteTorrentRequestDto deleteTorrentRequestDto) {
        torrentService.removeUserTorrent(deleteTorrentRequestDto);
        return AjaxResult.success();
    }

    /**
     * 添加rss订阅
     */
    @PostMapping({"/addRss"})
    @ApiOperation(value="添加rss订阅")
    public AjaxResult addRss(@Valid @RequestBody AddRssRequestDto addRssRequestDto) {
        torrentService.addRss(addRssRequestDto);
        return AjaxResult.success();
    }

    /**
     * 更新rss订阅信息
     */
    @PostMapping({"/updateRss"})
    @ApiOperation(value="更新rss订阅信息")
    public AjaxResult updateRss(@Valid @RequestBody UpdateRssRequestDto updateRssRequestDto) {
        torrentService.updateRss(updateRssRequestDto);
        return AjaxResult.success();
    }

    /**
     * 移除rss订阅
     */
    @PostMapping({"/removeRss"})
    @ApiOperation(value="移除rss订阅")
    public AjaxResult removeRss(@Valid @RequestBody DeleteRssRequestDto deleteRssRequestDto) {
        torrentService.removeRss(deleteRssRequestDto);
        return AjaxResult.success();
    }
}
