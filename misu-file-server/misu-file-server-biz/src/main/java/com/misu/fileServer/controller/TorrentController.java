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
     * 获取磁力链接详情
     */
    @GetMapping({"/getTorrentDetail"})
    @ApiOperation(value="获取磁力链接详情")
    public AjaxResult getTorrentDetail(@Valid DeleteTorrentRequestDto deleteTorrentRequestDto) {
        return AjaxResult.success(torrentService.getTorrentDetail(deleteTorrentRequestDto));
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
     * 获取RSS条目历史
     */
    @GetMapping({"/getRssItems"})
    @ApiOperation(value="获取RSS条目历史")
    public AjaxResult getRssItems(@Valid RssItemQueryRequestDto rssItemQueryRequestDto) {
        return AjaxResult.success(torrentService.getRssItems(rssItemQueryRequestDto));
    }

    /**
     * 手动刷新RSS订阅
     */
    @PostMapping({"/refreshRss"})
    @ApiOperation(value="手动刷新RSS订阅")
    public AjaxResult refreshRss(@Valid @RequestBody RssDetailRequestDto rssDetailRequestDto) {
        torrentService.refreshRss(rssDetailRequestDto);
        return AjaxResult.success();
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
     * 批量更新磁力链接
     */
    @PostMapping({"/batchUpdateUserTorrent"})
    @ApiOperation(value="批量更新磁力链接")
    public AjaxResult batchUpdateUserTorrent(@Valid @RequestBody BatchUpdateTorrentRequestDto batchUpdateTorrentRequestDto) {
        torrentService.batchUpdateUserTorrent(batchUpdateTorrentRequestDto);
        return AjaxResult.success();
    }

    /**
     * 强制刷新磁力链接状态
     */
    @PostMapping({"/refreshUserTorrentState"})
    @ApiOperation(value="强制刷新磁力链接状态")
    public AjaxResult refreshUserTorrentState(@Valid @RequestBody DeleteTorrentRequestDto deleteTorrentRequestDto) {
        return AjaxResult.success(torrentService.refreshUserTorrentState(deleteTorrentRequestDto));
    }

    /**
     * 删除服务器磁力任务
     */
    @PostMapping({"/deleteServerTorrent"})
    @ApiOperation(value="删除服务器磁力任务")
    public AjaxResult deleteServerTorrent(@Valid @RequestBody DeleteServerTorrentRequestDto deleteServerTorrentRequestDto) {
        torrentService.deleteServerTorrent(deleteServerTorrentRequestDto);
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
     * 重试同步磁力文件
     */
    @PostMapping({"/retryUserTorrentSync"})
    @ApiOperation(value="重试同步磁力文件")
    public AjaxResult retryUserTorrentSync(@Valid @RequestBody DeleteTorrentRequestDto deleteTorrentRequestDto) {
        torrentService.retryUserTorrentSync(deleteTorrentRequestDto);
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

    /**
     * 获取RSS规则列表
     */
    @GetMapping({"/getRssRuleList"})
    @ApiOperation(value="获取RSS规则列表")
    public AjaxResult getRssRuleList(@Valid RssDetailRequestDto rssDetailRequestDto) {
        return AjaxResult.success(torrentService.getRssRuleList(rssDetailRequestDto));
    }

    /**
     * 添加RSS规则
     */
    @PostMapping({"/addRssRule"})
    @ApiOperation(value="添加RSS规则")
    public AjaxResult addRssRule(@Valid @RequestBody AddRssRuleRequestDto addRssRuleRequestDto) {
        torrentService.addRssRule(addRssRuleRequestDto);
        return AjaxResult.success();
    }

    /**
     * 更新RSS规则
     */
    @PostMapping({"/updateRssRule"})
    @ApiOperation(value="更新RSS规则")
    public AjaxResult updateRssRule(@Valid @RequestBody UpdateRssRuleRequestDto updateRssRuleRequestDto) {
        torrentService.updateRssRule(updateRssRuleRequestDto);
        return AjaxResult.success();
    }

    /**
     * 删除RSS规则
     */
    @PostMapping({"/removeRssRule"})
    @ApiOperation(value="删除RSS规则")
    public AjaxResult removeRssRule(@Valid @RequestBody DeleteRssRuleRequestDto deleteRssRuleRequestDto) {
        torrentService.removeRssRule(deleteRssRuleRequestDto);
        return AjaxResult.success();
    }

    /**
     * 批量下载RSS条目
     */
    @PostMapping({"/batchDownloadRssItems"})
    @ApiOperation(value="批量下载RSS条目")
    public AjaxResult batchDownloadRssItems(@Valid @RequestBody BatchDownloadRssItemsRequestDto batchDownloadRssItemsRequestDto) {
        torrentService.batchDownloadRssItems(batchDownloadRssItemsRequestDto);
        return AjaxResult.success();
    }
}
