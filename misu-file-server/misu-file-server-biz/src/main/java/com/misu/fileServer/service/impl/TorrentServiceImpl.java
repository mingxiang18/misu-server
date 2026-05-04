package com.misu.fileServer.service.impl;

import com.alibaba.fastjson2.TypeReference;
import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.dao.TorrentDao;
import com.misu.fileServer.domain.dto.*;
import com.misu.fileServer.domain.entity.RssInfo;
import com.misu.fileServer.domain.entity.RssItem;
import com.misu.fileServer.domain.entity.RssRule;
import com.misu.fileServer.domain.entity.TorrentInfo;
import com.misu.fileServer.domain.entity.TorrentUserRelation;
import com.misu.fileServer.repository.RssItemRepository;
import com.misu.fileServer.repository.RssRuleRepository;
import com.misu.fileServer.service.FileService;
import com.misu.fileServer.service.TorrentService;
import com.misu.fileServer.util.torrent.QBitTorrentApi;
import com.misu.fileServer.util.torrent.request.AddTorrentRequest;
import com.misu.fileServer.util.torrent.request.DeleteTorrentRequest;
import com.misu.fileServer.util.torrent.request.TorrentHashRequest;
import com.misu.fileServer.util.torrent.request.TorrentInfoRequest;
import com.misu.fileServer.util.torrent.response.TorrentInfoResponse;
import com.misu.framework.fileClient.FileClientApi;
import com.misu.framework.fileClient.domain.FileInfo;
import com.misu.framework.util.PageUtils;
import com.misu.framework.util.RestUtils;
import com.misu.security.constant.UserRole;
import com.misu.security.dto.LoginUser;
import com.misu.security.utils.AuthorityUtil;
import com.misu.security.utils.LoginMessageUtil;
import com.rometools.rome.feed.synd.SyndEnclosure;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import jakarta.annotation.Resource;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base32;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * 磁力相关Service
 *
 * @author misu
 */
@Slf4j
@Service
public class TorrentServiceImpl implements TorrentService {

    @Resource
    private TorrentDao torrentDao;

    @Resource
    @Qualifier("fileServerDataSource")
    private DataSource fileServerDataSource;

    @Resource
    private QBitTorrentApi qBitTorrentApi;

    @Resource
    private RssItemRepository rssItemRepository;

    @Resource
    private RssRuleRepository rssRuleRepository;

    @Resource
    private FileClientApi fileClientApi;

    @Resource
    private FileService fileService;

    @Resource
    private RestUtils restUtils;

    @Resource
    private ThreadPoolTaskExecutor fileExecutor;

    @Value("${file-server.qBitTorrent.localPath}")
    private String qBitTorrentLocalPath;

    @Value("${file-server.qBitTorrent.remoteEnable}")
    private Boolean remoteEnable;

    @Value("${file-server.qBitTorrent.remoteDownloadPath}")
    private String qBitTorrentRemoteDownloadPath;

    /**
     * 定时状态更新锁
     */
    private final ReentrantLock stateUpdateLock = new ReentrantLock();
    /**
     * 文件同步锁
     */
    private final ReentrantLock fileSyncLock = new ReentrantLock();
    /**
     * RSS状态更新锁
     */
    private final ReentrantLock rssUpdateLock = new ReentrantLock();

    @Override
    public Page<UserTorrentDetailDto> getTorrentList(UserTorrentQueryRequestDto userTorrentQueryRequestDto) {
        //条件添加登陆用户id
        userTorrentQueryRequestDto.setUserId(LoginMessageUtil.getLoginUser().get().getUserId().toString());
        //获取分页参数
        PageRequest pageRequest = PageUtils.getPageRequest();
        //执行查询
        Page<UserTorrentDetailDto> userTorrentDetailDtoList = torrentDao.selectUserTorrentByPage(userTorrentQueryRequestDto, pageRequest);

        //过滤出未完成下载的torrent状态
        List<UserTorrentDetailDto> notCompletedTorrentList = userTorrentDetailDtoList.stream()
                .filter(userTorrentDetailDto -> userTorrentDetailDto.getServerFileState() < 30)
                .toList();
        //查询qBitTorrent内该部分torrent的下载状态
        if (CollectionUtils.isNotEmpty(notCompletedTorrentList)) {
            try {
                //封装要查询的torrentHash值
                String torrentHashes = notCompletedTorrentList.stream()
                        .map(UserTorrentDetailDto::getTorrentHash)
                        .collect(Collectors.joining("|"));
                TorrentInfoRequest torrentInfoRequest = new TorrentInfoRequest();
                torrentInfoRequest.setHashes(torrentHashes);
                List<TorrentInfoResponse> torrentList = qBitTorrentApi.getTorrentList(torrentInfoRequest);
                if (CollectionUtils.isNotEmpty(torrentList)) {
                    //封装到对应的记录
                    for (TorrentInfoResponse torrentInfoResponse : torrentList) {
                        notCompletedTorrentList.forEach(userTorrentDetailDto -> {
                            if (userTorrentDetailDto.getTorrentHash().equals(torrentInfoResponse.getHash())) {
                                packageTorrentRuntimeInfo(userTorrentDetailDto, torrentInfoResponse);
                            }
                        });
                    }
                }
            }catch (Exception e) {
                log.error("查询qBitTorrent获取torrent状态列表失败", e);
            }
        }

        //如果存在已下载完成但是未同步到用户文件的数据，异步执行同步
        if (userTorrentDetailDtoList.stream().anyMatch(userTorrentDetailDto ->
                userTorrentDetailDto.getServerFileState() == 30 && userTorrentDetailDto.getUserFileState() == 0)) {
            //异步执行一次文件同步
            fileExecutor.execute(this::moveCompletedTorrentToUserDirectory);
        }

        return userTorrentDetailDtoList;
    }

    @Override
    public UserTorrentDetailDto getTorrentDetail(DeleteTorrentRequestDto deleteTorrentRequestDto) {
        UserTorrentDetailDto userTorrentDetailDto = getCurrentUserTorrent(deleteTorrentRequestDto.getUserTorrentId());
        refreshQBitTorrentInfo(userTorrentDetailDto, true);
        return userTorrentDetailDto;
    }

    private UserTorrentDetailDto getCurrentUserTorrent(Long userTorrentId) {
        UserTorrentQueryRequestDto userTorrentQueryRequestDto = new UserTorrentQueryRequestDto();
        userTorrentQueryRequestDto.setUserId(LoginMessageUtil.getLoginUser().get().getUserId().toString());
        userTorrentQueryRequestDto.setUserTorrentId(userTorrentId);
        List<UserTorrentDetailDto> userTorrentDetailDtoList = torrentDao.selectUserTorrent(userTorrentQueryRequestDto);
        if (CollectionUtils.isEmpty(userTorrentDetailDtoList)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "当前磁力文件不存在");
        }
        return userTorrentDetailDtoList.get(0);
    }

    private void refreshQBitTorrentInfo(UserTorrentDetailDto userTorrentDetailDto, boolean ignoreError) {
        try {
            TorrentInfoRequest torrentInfoRequest = new TorrentInfoRequest();
            torrentInfoRequest.setHashes(userTorrentDetailDto.getTorrentHash());
            List<TorrentInfoResponse> torrentInfoResponseList = qBitTorrentApi.getTorrentList(torrentInfoRequest);
            if (CollectionUtils.isEmpty(torrentInfoResponseList)) {
                return;
            }
            TorrentInfoResponse torrentInfoResponse = torrentInfoResponseList.get(0);
            packageTorrentRuntimeInfo(userTorrentDetailDto, torrentInfoResponse);
            updateTorrentInfoFromQBitTorrent(userTorrentDetailDto, torrentInfoResponse);
        }catch (Exception e) {
            if (ignoreError) {
                log.warn("刷新qBitTorrent状态失败，hash：{}", userTorrentDetailDto.getTorrentHash(), e);
            }else {
                throw e;
            }
        }
    }

    private void packageTorrentRuntimeInfo(UserTorrentDetailDto userTorrentDetailDto, TorrentInfoResponse torrentInfoResponse) {
        userTorrentDetailDto.setTorrentName(torrentInfoResponse.getName());
        userTorrentDetailDto.setTotalSize(torrentInfoResponse.getTotalSize());
        userTorrentDetailDto.setServerFileProgress(torrentInfoResponse.getProgress());
        userTorrentDetailDto.setServerFileDownloadSpeed(torrentInfoResponse.getDlSpeed());
        userTorrentDetailDto.setServerFileUploadSpeed(torrentInfoResponse.getUpSpeed());
        userTorrentDetailDto.setTorrentState(torrentInfoResponse.getState());
        userTorrentDetailDto.setEta(torrentInfoResponse.getEta());
        userTorrentDetailDto.setDownloaded(torrentInfoResponse.getDownloaded());
        userTorrentDetailDto.setUploaded(torrentInfoResponse.getUploaded());
        userTorrentDetailDto.setCompleted(torrentInfoResponse.getCompleted());
        userTorrentDetailDto.setAmountLeft(torrentInfoResponse.getAmountLeft());
        userTorrentDetailDto.setNumSeeds(torrentInfoResponse.getNumSeeds());
        userTorrentDetailDto.setNumLeechs(torrentInfoResponse.getNumLeechs());
        userTorrentDetailDto.setNumComplete(torrentInfoResponse.getNumComplete());
        userTorrentDetailDto.setNumIncomplete(torrentInfoResponse.getNumIncomplete());
        userTorrentDetailDto.setTracker(torrentInfoResponse.getTracker());
        userTorrentDetailDto.setSavePath(torrentInfoResponse.getSavePath());
        userTorrentDetailDto.setContentPath(torrentInfoResponse.getContentPath());
        userTorrentDetailDto.setCategory(torrentInfoResponse.getCategory());
        userTorrentDetailDto.setTags(torrentInfoResponse.getTags());
        userTorrentDetailDto.setAddedOn(torrentInfoResponse.getAddedOn());
        userTorrentDetailDto.setCompletionOn(torrentInfoResponse.getCompletionOn());
        userTorrentDetailDto.setLastActivity(torrentInfoResponse.getLastActivity());
        userTorrentDetailDto.setRatio(torrentInfoResponse.getRatio());
        userTorrentDetailDto.setDlLimit(torrentInfoResponse.getDlLimit());
        userTorrentDetailDto.setUpLimit(torrentInfoResponse.getUpLimit());
        if (torrentInfoResponse.getProgress() != null && torrentInfoResponse.getProgress() >= 1) {
            userTorrentDetailDto.setServerFileState(30);
        }
    }

    private void updateTorrentInfoFromQBitTorrent(UserTorrentDetailDto userTorrentDetailDto, TorrentInfoResponse torrentInfoResponse) {
        TorrentInfo torrentInfo = new TorrentInfo();
        torrentInfo.setTorrentHash(userTorrentDetailDto.getTorrentHash());
        torrentInfo.setTorrentName(torrentInfoResponse.getName());
        torrentInfo.setTotalSize(torrentInfoResponse.getTotalSize());
        torrentInfo.setDownloadPath(torrentInfoResponse.getSavePath());
        if (TorrentInfoResponse.COMPLETE_STATE_LIST.contains(torrentInfoResponse.getState())
                || (torrentInfoResponse.getProgress() != null && torrentInfoResponse.getProgress() >= 1)) {
            torrentInfo.setState(30);
        }else if (TorrentInfoResponse.ERROR_STATE_LIST.contains(torrentInfoResponse.getState())) {
            torrentInfo.setState(99);
            if ("missingFiles".equals(torrentInfoResponse.getState())) {
                torrentInfo.setRemark("文件丢失");
            }else {
                torrentInfo.setRemark("下载失败");
            }
        }else if (TorrentInfoResponse.DOWNLOADING_STATE_LIST.contains(torrentInfoResponse.getState())
                || TorrentInfoResponse.NOT_START_STATE_LIST.contains(torrentInfoResponse.getState())) {
            torrentInfo.setState(userTorrentDetailDto.getServerFileState() == 10 ? 10 : 20);
        }
        torrentDao.updateTorrentInfo(torrentInfo);
    }

    @Override
    public Page<RssInfoDto> getRssList(RssQueryRequestDto rssQueryRequestDto) {
        rssQueryRequestDto.setCreatorId(LoginMessageUtil.getLoginUser().get().getUserId().toString());
        return torrentDao.selectRssListByPage(rssQueryRequestDto, PageUtils.getPageRequest());
    }

    @Override
    public RssDetailDto getRssDetail(RssDetailRequestDto rssDetailRequestDto) {
        RssInfoDto rssInfoDto = getCurrentUserRss(rssDetailRequestDto.getRssId());

        RssDetailDto rssDetailDto = new RssDetailDto();
        rssDetailDto.setId(rssInfoDto.getId());
        rssDetailDto.setRssUrl(rssInfoDto.getRssUrl());
        rssDetailDto.setRssName(rssInfoDto.getRssName());
        rssDetailDto.setDownloadPath(rssInfoDto.getDownloadPath());
        rssDetailDto.setState(rssInfoDto.getState());
        rssDetailDto.setRemark(rssInfoDto.getRemark());
        rssDetailDto.setRssTorrentRelationList(rssItemRepository
                .findByRssIdOrderByPublishTimeDescCreateTimeDesc(rssInfoDto.getId())
                .stream()
                .map(this::toRssTorrentRelationDto)
                .toList());
        rssDetailDto.setRssRuleList(rssRuleRepository
                .findByRssIdOrderByCreateTimeDesc(rssInfoDto.getId())
                .stream()
                .map(this::toRssRuleDto)
                .toList());
        return rssDetailDto;
    }

    @Override
    public Page<RssItemDto> getRssItems(RssItemQueryRequestDto rssItemQueryRequestDto) {
        getCurrentUserRss(rssItemQueryRequestDto.getRssId());
        PageRequest pageRequest = PageUtils.getPageRequest();
        List<RssItemDto> filteredItemList = rssItemRepository
                .findByRssIdOrderByPublishTimeDescCreateTimeDesc(rssItemQueryRequestDto.getRssId())
                .stream()
                .filter(rssItem -> rssItemQueryRequestDto.getMatchState() == null
                        || rssItemQueryRequestDto.getMatchState().equals(rssItem.getMatchState()))
                .filter(rssItem -> rssItemQueryRequestDto.getDownloadState() == null
                        || rssItemQueryRequestDto.getDownloadState().equals(rssItem.getDownloadState()))
                .filter(rssItem -> StringUtils.isBlank(rssItemQueryRequestDto.getKeyword())
                        || StringUtils.containsIgnoreCase(StringUtils.defaultString(rssItem.getTitle()), rssItemQueryRequestDto.getKeyword())
                        || StringUtils.containsIgnoreCase(StringUtils.defaultString(rssItem.getDescription()), rssItemQueryRequestDto.getKeyword()))
                .map(this::toRssItemDto)
                .toList();
        int start = (int) pageRequest.getOffset();
        int end = Math.min(start + pageRequest.getPageSize(), filteredItemList.size());
        List<RssItemDto> pageContent = start >= filteredItemList.size() ? Collections.emptyList() : filteredItemList.subList(start, end);
        return new PageImpl<>(pageContent, pageRequest, filteredItemList.size());
    }

    @Override
    public void refreshRss(RssDetailRequestDto rssDetailRequestDto) {
        RssInfoDto rssInfoDto = getCurrentUserRss(rssDetailRequestDto.getRssId());
        refreshRssInternal(rssInfoDto, false, true);
    }

    @Override
    public List<RssRuleDto> getRssRuleList(RssDetailRequestDto rssDetailRequestDto) {
        getCurrentUserRss(rssDetailRequestDto.getRssId());
        return rssRuleRepository.findByRssIdOrderByCreateTimeDesc(rssDetailRequestDto.getRssId())
                .stream()
                .map(this::toRssRuleDto)
                .toList();
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void addRssRule(AddRssRuleRequestDto addRssRuleRequestDto) {
        RssInfoDto rssInfoDto = getCurrentUserRss(addRssRuleRequestDto.getRssId());
        RssRule rssRule = new RssRule();
        packageRssRule(rssRule, addRssRuleRequestDto, rssInfoDto);
        rssRule.setCreateTime(LocalDateTime.now());
        rssRule.setUpdateTime(LocalDateTime.now());
        rssRuleRepository.save(rssRule);
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void updateRssRule(UpdateRssRuleRequestDto updateRssRuleRequestDto) {
        RssRule rssRule = rssRuleRepository.findById(updateRssRuleRequestDto.getRuleId())
                .orElseThrow(() -> new ServiceException(HttpStatus.BAD_REQUEST, "规则不存在"));
        RssInfoDto rssInfoDto = getCurrentUserRss(rssRule.getRssId());
        if (!rssRule.getRssId().equals(updateRssRuleRequestDto.getRssId())) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "规则所属订阅不一致");
        }
        packageRssRule(rssRule, updateRssRuleRequestDto, rssInfoDto);
        rssRule.setUpdateTime(LocalDateTime.now());
        rssRuleRepository.save(rssRule);
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void removeRssRule(DeleteRssRuleRequestDto deleteRssRuleRequestDto) {
        RssRule rssRule = rssRuleRepository.findById(deleteRssRuleRequestDto.getRuleId())
                .orElseThrow(() -> new ServiceException(HttpStatus.BAD_REQUEST, "规则不存在"));
        getCurrentUserRss(rssRule.getRssId());
        rssRuleRepository.delete(rssRule);
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void batchDownloadRssItems(BatchDownloadRssItemsRequestDto batchDownloadRssItemsRequestDto) {
        String userId = LoginMessageUtil.getLoginUser().get().getUserId().toString();
        Map<Long, RssInfoDto> rssInfoDtoMap = new HashMap<>();
        for (Long itemId : batchDownloadRssItemsRequestDto.getItemIdList()) {
            RssItem rssItem = rssItemRepository.findById(itemId)
                    .orElseThrow(() -> new ServiceException(HttpStatus.BAD_REQUEST, "RSS条目不存在"));
            RssInfoDto rssInfoDto = rssInfoDtoMap.computeIfAbsent(rssItem.getRssId(), this::getCurrentUserRss);
            downloadRssItem(rssItem, rssInfoDto, getMatchedRule(rssItem), userId);
        }
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void addUserTorrent(AddTorrentRequestDto addTorrentRequestDto) {
        addUserTorrentForUser(addTorrentRequestDto, LoginMessageUtil.getLoginUser().get().getUserId().toString());
    }

    private void addUserTorrentForUser(AddTorrentRequestDto addTorrentRequestDto, String userId) {
        String torrentHash = getTorrentHashFromUrl(addTorrentRequestDto.getTorrentUrl());
        if (StringUtils.isBlank(torrentHash)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "磁力链接不合法");
        }

        //数据库获取该磁力链接数据
        Optional<TorrentInfo> torrentInfoOptional = torrentDao.selectTorrentInfoByHash(torrentHash);
        TorrentInfo torrentInfo = null;

        //如果不存在，则保存到数据库
        if (torrentInfoOptional.isEmpty()) {
            torrentInfo = new TorrentInfo();
            torrentInfo.setTorrentHash(torrentHash);
            torrentInfo.setTorrentUrl(addTorrentRequestDto.getTorrentUrl());
            torrentInfo.setState(0);
            torrentInfo.setCreatorId(userId);
            torrentInfo.setCreateTime(LocalDateTime.now());
            torrentDao.saveTorrentInfo(torrentInfo);

            //添加到qBitTorrent的下载列表
            AddTorrentRequest addTorrentRequest = new AddTorrentRequest();
            addTorrentRequest.setUrls(addTorrentRequestDto.getTorrentUrl());
            addTorrentRequest.setAutoTMM(true);
            qBitTorrentApi.addNewTorrent(addTorrentRequest);
        }else {
            torrentInfo = torrentInfoOptional.get();
        }

        //查询用户和磁力链接是否有关联
        UserTorrentQueryRequestDto userTorrentQueryRequestDto = new UserTorrentQueryRequestDto();
        userTorrentQueryRequestDto.setUserId(userId);
        userTorrentQueryRequestDto.setTorrentHash(torrentHash);
        userTorrentQueryRequestDto.setUserFilePath(addTorrentRequestDto.getUserFilePath());
        userTorrentQueryRequestDto.setUserFileState(0);
        List<UserTorrentDetailDto> userTorrentList = torrentDao.selectUserTorrent(userTorrentQueryRequestDto);
        if (CollectionUtils.isEmpty(userTorrentList)) {
            TorrentUserRelation torrentUserRelation = new TorrentUserRelation();
            torrentUserRelation.setUserId(userId);
            torrentUserRelation.setUserFilePath(addTorrentRequestDto.getUserFilePath());
            torrentUserRelation.setTorrentHash(torrentHash);
            torrentUserRelation.setState(0);
            torrentUserRelation.setCreateTime(LocalDateTime.now());
            torrentDao.saveTorrentUserRelation(torrentUserRelation);
        }else {
            throw new ServiceException(HttpStatus.NOT_MODIFIED, "当前目录已存在该磁力链接的下载记录，请勿重复添加");
        }

        //异步执行一次文件同步
        fileExecutor.execute(this::moveCompletedTorrentToUserDirectory);
    }

    /**
     * 从magnet磁力链接中获取hash值
     */
    private static String getTorrentHashFromUrl(String torrentUrl) {
        if (StringUtils.isBlank(torrentUrl) || !StringUtils.startsWithIgnoreCase(torrentUrl, "magnet:?xt=urn:btih:")) {
            return null;
        }
        //用正则从magnet的url中获取hash值，不能包含后面的参数
        String torrentHash = torrentUrl.replaceAll("magnet:\\?xt=urn:btih:", "").split("&")[0];
        //有些hash是base32编码的，需要转为16进制的40位hash
        if (torrentHash.length() == 32) {
            // 解码 Base32 字符串
            Base32 base32 = new Base32();
            byte[] decodedBytes = base32.decode(torrentHash);

            // 将解码后的字节数组转换为十六进制字符串
            torrentHash = Hex.encodeHexString(decodedBytes);
        }
        return torrentHash;
    }

    private RssInfoDto getCurrentUserRss(Long rssId) {
        RssQueryRequestDto rssQueryRequestDto = new RssQueryRequestDto();
        rssQueryRequestDto.setRssId(rssId);
        rssQueryRequestDto.setCreatorId(LoginMessageUtil.getLoginUser().get().getUserId().toString());
        List<RssInfoDto> rssInfoDtoList = torrentDao.selectRssList(rssQueryRequestDto);
        if (CollectionUtils.isEmpty(rssInfoDtoList)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "RSS订阅不存在");
        }
        return rssInfoDtoList.get(0);
    }

    private void packageRssRule(RssRule rssRule, AddRssRuleRequestDto rssRuleRequestDto, RssInfoDto rssInfoDto) {
        if (StringUtils.isBlank(rssRuleRequestDto.getIncludeKeywords())
                && StringUtils.isBlank(rssRuleRequestDto.getExcludeKeywords())
                && StringUtils.isBlank(rssRuleRequestDto.getRegex())) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "规则至少需要包含关键词、排除关键词或正则之一");
        }
        if (StringUtils.isNotBlank(rssRuleRequestDto.getRegex())) {
            try {
                Pattern.compile(rssRuleRequestDto.getRegex());
            } catch (PatternSyntaxException e) {
                throw new ServiceException(HttpStatus.BAD_REQUEST, "正则表达式不合法");
            }
        }
        rssRule.setRssId(rssRuleRequestDto.getRssId());
        rssRule.setRuleName(rssRuleRequestDto.getRuleName());
        rssRule.setIncludeKeywords(rssRuleRequestDto.getIncludeKeywords());
        rssRule.setExcludeKeywords(rssRuleRequestDto.getExcludeKeywords());
        rssRule.setRegex(rssRuleRequestDto.getRegex());
        rssRule.setDownloadPath(StringUtils.defaultIfBlank(rssRuleRequestDto.getDownloadPath(), rssInfoDto.getDownloadPath()));
        rssRule.setEnabled(rssRuleRequestDto.getEnabled() == null || rssRuleRequestDto.getEnabled());
        rssRule.setAutoDownload(rssRuleRequestDto.getAutoDownload() != null && rssRuleRequestDto.getAutoDownload());
        rssRule.setRemark(rssRuleRequestDto.getRemark());
    }

    private RssRule getMatchedRule(RssItem rssItem) {
        if (rssItem.getMatchedRuleId() == null) {
            return null;
        }
        return rssRuleRepository.findById(rssItem.getMatchedRuleId()).orElse(null);
    }

    private RssRuleDto toRssRuleDto(RssRule rssRule) {
        RssRuleDto rssRuleDto = new RssRuleDto();
        rssRuleDto.setId(rssRule.getId());
        rssRuleDto.setRssId(rssRule.getRssId());
        rssRuleDto.setRuleName(rssRule.getRuleName());
        rssRuleDto.setIncludeKeywords(rssRule.getIncludeKeywords());
        rssRuleDto.setExcludeKeywords(rssRule.getExcludeKeywords());
        rssRuleDto.setRegex(rssRule.getRegex());
        rssRuleDto.setDownloadPath(rssRule.getDownloadPath());
        rssRuleDto.setEnabled(rssRule.getEnabled());
        rssRuleDto.setAutoDownload(rssRule.getAutoDownload());
        rssRuleDto.setRemark(rssRule.getRemark());
        rssRuleDto.setCreateTime(rssRule.getCreateTime());
        return rssRuleDto;
    }

    private RssItemDto toRssItemDto(RssItem rssItem) {
        RssItemDto rssItemDto = new RssItemDto();
        rssItemDto.setId(rssItem.getId());
        rssItemDto.setRssId(rssItem.getRssId());
        rssItemDto.setTitle(rssItem.getTitle());
        rssItemDto.setTorrentHash(rssItem.getTorrentHash());
        rssItemDto.setTorrentUrl(rssItem.getTorrentUrl());
        rssItemDto.setDescription(rssItem.getDescription());
        rssItemDto.setAuthor(rssItem.getAuthor());
        rssItemDto.setMatchState(rssItem.getMatchState());
        rssItemDto.setDownloadState(rssItem.getDownloadState());
        rssItemDto.setMatchedRuleId(rssItem.getMatchedRuleId());
        rssItemDto.setErrorMessage(rssItem.getErrorMessage());
        rssItemDto.setPublishTime(rssItem.getPublishTime());
        rssItemDto.setUpdatedTime(rssItem.getUpdatedTime());
        return rssItemDto;
    }

    private RssTorrentRelationDto toRssTorrentRelationDto(RssItem rssItem) {
        RssTorrentRelationDto rssTorrentRelationDto = new RssTorrentRelationDto();
        rssTorrentRelationDto.setTitle(rssItem.getTitle());
        rssTorrentRelationDto.setTorrentHash(rssItem.getTorrentHash());
        rssTorrentRelationDto.setTorrentUrl(rssItem.getTorrentUrl());
        rssTorrentRelationDto.setDescription(rssItem.getDescription());
        rssTorrentRelationDto.setAuthor(rssItem.getAuthor());
        rssTorrentRelationDto.setDownloadState(rssItem.getDownloadState());
        if (rssItem.getPublishTime() != null) {
            rssTorrentRelationDto.setPublishDate(Date.from(rssItem.getPublishTime().atZone(ZoneId.systemDefault()).toInstant()));
        }
        if (rssItem.getUpdatedTime() != null) {
            rssTorrentRelationDto.setUpdatedDate(Date.from(rssItem.getUpdatedTime().atZone(ZoneId.systemDefault()).toInstant()));
        }
        return rssTorrentRelationDto;
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void updateUserTorrent(UpdateTorrentRequestDto updateTorrentRequestDto) {
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
        //查询用户的磁力关联信息
        UserTorrentQueryRequestDto userTorrentQueryRequestDto = new UserTorrentQueryRequestDto();
        userTorrentQueryRequestDto.setUserId(loginUser.getUserId().toString());
        userTorrentQueryRequestDto.setUserTorrentId(updateTorrentRequestDto.getUserTorrentId());
        List<UserTorrentDetailDto> userTorrentDetailDtoList = torrentDao.selectUserTorrent(userTorrentQueryRequestDto);

        //如果不存在则结束
        if (CollectionUtils.isEmpty(userTorrentDetailDtoList)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "当前磁力文件不存在");
        }

        UserTorrentDetailDto userTorrentDetailDto = userTorrentDetailDtoList.get(0);

        //如果已同步
        if (userTorrentDetailDto.getUserFileState() == 1) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "当前磁力文件已同步到用户文件夹中，无法修改，请手动在文件目录中修改");
        }

        //判断用户是否是管理员
        if (!AuthorityUtil.hasAuthority(Arrays.asList(UserRole.FILE_ADMIN, UserRole.ADMIN))) {
            if (updateTorrentRequestDto.getServerFileState() != null) {
                throw new ServiceException(HttpStatus.BAD_REQUEST, "没有权限修改服务器文件状态");
            }
        }

        if (updateTorrentRequestDto.getServerFileState() != null
                && !Arrays.asList(10, 20).contains(updateTorrentRequestDto.getServerFileState())) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "不合法的服务器文件状态");
        }

        if (StringUtils.isNotBlank(updateTorrentRequestDto.getUserFilePath())) {
            UserTorrentDetailDto userTorrentRecord = userTorrentDetailDtoList.get(0);
            //查询用户目录是否已经关联该链接
            UserTorrentQueryRequestDto sameDirectoryQuery = new UserTorrentQueryRequestDto();
            sameDirectoryQuery.setUserId(LoginMessageUtil.getLoginUser().get().getUserId().toString());
            sameDirectoryQuery.setTorrentHash(userTorrentRecord.getTorrentHash());
            sameDirectoryQuery.setUserFilePath(updateTorrentRequestDto.getUserFilePath());
            List<UserTorrentDetailDto> userTorrentList = torrentDao.selectUserTorrent(sameDirectoryQuery);
            if (CollectionUtils.isNotEmpty(userTorrentList)) {
                for (UserTorrentDetailDto torrentDetailDto : userTorrentList) {
                    //如果id不相等，说明存在重复
                    if (!updateTorrentRequestDto.getUserTorrentId().equals(torrentDetailDto.getUserTorrentId())) {
                        throw new ServiceException(HttpStatus.BAD_REQUEST, "修改后的目录下已存在当前磁力下载记录，请勿重复添加");
                    }
                }
            }

            //更新用户文件路径
            TorrentUserRelation torrentUserRelation = new TorrentUserRelation();
            torrentUserRelation.setId(updateTorrentRequestDto.getUserTorrentId());
            torrentUserRelation.setUserFilePath(updateTorrentRequestDto.getUserFilePath());
            torrentDao.updateTorrentUserRelation(torrentUserRelation);
        }

        //如果有服务器文件状态则更新
        if (updateTorrentRequestDto.getServerFileState() != null) {
            //qBitTorrent修改状态
            if (updateTorrentRequestDto.getServerFileState() == 10) {
                qBitTorrentApi.stopTorrent(new TorrentHashRequest(userTorrentDetailDto.getTorrentHash()));
            }else if (updateTorrentRequestDto.getServerFileState() == 20) {
                qBitTorrentApi.startTorrent(new TorrentHashRequest(userTorrentDetailDto.getTorrentHash()));
            }

            torrentDao.updateTorrentState(userTorrentDetailDto.getTorrentHash(),
                    updateTorrentRequestDto.getServerFileState(),
                    null);
        }
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void batchUpdateUserTorrent(BatchUpdateTorrentRequestDto batchUpdateTorrentRequestDto) {
        for (Long userTorrentId : batchUpdateTorrentRequestDto.getUserTorrentIdList()) {
            UpdateTorrentRequestDto updateTorrentRequestDto = new UpdateTorrentRequestDto();
            updateTorrentRequestDto.setUserTorrentId(userTorrentId);
            updateTorrentRequestDto.setServerFileState(batchUpdateTorrentRequestDto.getServerFileState());
            updateUserTorrent(updateTorrentRequestDto);
        }
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public UserTorrentDetailDto refreshUserTorrentState(DeleteTorrentRequestDto deleteTorrentRequestDto) {
        UserTorrentDetailDto userTorrentDetailDto = getCurrentUserTorrent(deleteTorrentRequestDto.getUserTorrentId());
        refreshQBitTorrentInfo(userTorrentDetailDto, false);
        return getTorrentDetail(deleteTorrentRequestDto);
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void deleteServerTorrent(DeleteServerTorrentRequestDto deleteServerTorrentRequestDto) {
        if (!AuthorityUtil.hasAuthority(Arrays.asList(UserRole.FILE_ADMIN, UserRole.ADMIN))) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "没有权限删除服务器磁力任务");
        }

        UserTorrentDetailDto userTorrentDetailDto = getCurrentUserTorrent(deleteServerTorrentRequestDto.getUserTorrentId());
        DeleteTorrentRequest deleteTorrentRequest = new DeleteTorrentRequest();
        deleteTorrentRequest.setHashes(userTorrentDetailDto.getTorrentHash());
        deleteTorrentRequest.setDeleteFiles(deleteServerTorrentRequestDto.getDeleteFiles());
        qBitTorrentApi.deleteTorrent(deleteTorrentRequest);
        TorrentInfo torrentInfo = new TorrentInfo();
        torrentInfo.setTorrentHash(userTorrentDetailDto.getTorrentHash());
        torrentInfo.setState(99);
        torrentInfo.setRemark("服务器任务已删除");
        torrentDao.updateTorrentInfo(torrentInfo);
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void removeUserTorrent(DeleteTorrentRequestDto deleteTorrentRequestDto) {
        //查询用户的磁力关联信息
        UserTorrentQueryRequestDto userTorrentQueryRequestDto = new UserTorrentQueryRequestDto();
        userTorrentQueryRequestDto.setUserId(LoginMessageUtil.getLoginUser().get().getUserId().toString());
        userTorrentQueryRequestDto.setUserTorrentId(deleteTorrentRequestDto.getUserTorrentId());
        List<UserTorrentDetailDto> userTorrentDetailDtoList = torrentDao.selectUserTorrent(userTorrentQueryRequestDto);

        //如果不存在则结束
        if (CollectionUtils.isEmpty(userTorrentDetailDtoList)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "当前磁力文件不存在");
        }

        UserTorrentDetailDto userTorrentDetailDto = userTorrentDetailDtoList.get(0);

        //如果已同步
        if (userTorrentDetailDto.getUserFileState() == 1) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "当前磁力文件已同步到用户文件夹中，无法删除，请手动在文件目录中操作");
        }

        //删除用户与磁力链接关联
        torrentDao.deleteTorrentUserRelation(deleteTorrentRequestDto.getUserTorrentId());
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void retryUserTorrentSync(DeleteTorrentRequestDto deleteTorrentRequestDto) {
        UserTorrentQueryRequestDto userTorrentQueryRequestDto = new UserTorrentQueryRequestDto();
        userTorrentQueryRequestDto.setUserId(LoginMessageUtil.getLoginUser().get().getUserId().toString());
        userTorrentQueryRequestDto.setUserTorrentId(deleteTorrentRequestDto.getUserTorrentId());
        List<UserTorrentDetailDto> userTorrentDetailDtoList = torrentDao.selectUserTorrent(userTorrentQueryRequestDto);
        if (CollectionUtils.isEmpty(userTorrentDetailDtoList)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "当前磁力文件不存在");
        }

        UserTorrentDetailDto userTorrentDetailDto = userTorrentDetailDtoList.get(0);
        if (userTorrentDetailDto.getUserFileState() == 1) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "当前磁力文件已同步，无需重试");
        }
        if (userTorrentDetailDto.getServerFileState() != 30) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "服务器文件未下载完成，无法重试同步");
        }

        TorrentUserRelation torrentUserRelation = new TorrentUserRelation();
        torrentUserRelation.setId(deleteTorrentRequestDto.getUserTorrentId());
        torrentUserRelation.setState(0);
        torrentUserRelation.setFailedReason("等待重新同步");
        torrentDao.updateTorrentUserRelation(torrentUserRelation);

        fileExecutor.execute(this::moveCompletedTorrentToUserDirectory);
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void addRss(AddRssRequestDto addRssRequestDto) {
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();

        RssQueryRequestDto rssQueryRequestDto = new RssQueryRequestDto();
        rssQueryRequestDto.setCreatorId(loginUser.getUserId().toString());
        rssQueryRequestDto.setRssUrl(addRssRequestDto.getRssUrl());
        List<RssInfoDto> rssInfoDtoList = torrentDao.selectRssList(rssQueryRequestDto);
        if (CollectionUtils.isNotEmpty(rssInfoDtoList)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "该rss链接已存在，请勿重复添加");
        }

        RssInfo rssInfo = new RssInfo();
        rssInfo.setRssUrl(addRssRequestDto.getRssUrl());
        rssInfo.setRssName(addRssRequestDto.getRssName());
        rssInfo.setDownloadPath(addRssRequestDto.getDownloadPath());
        rssInfo.setState(0);
        rssInfo.setCreatorId(loginUser.getUserId().toString());
        rssInfo.setCreateTime(LocalDateTime.now());
        torrentDao.saveRssInfo(rssInfo);
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void updateRss(UpdateRssRequestDto updateRssRequestDto) {
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();

        RssQueryRequestDto rssQueryRequestDto = new RssQueryRequestDto();
        rssQueryRequestDto.setCreatorId(loginUser.getUserId().toString());
        rssQueryRequestDto.setRssId(updateRssRequestDto.getRssId());
        List<RssInfoDto> rssInfoDtoList = torrentDao.selectRssList(rssQueryRequestDto);
        if (CollectionUtils.isEmpty(rssInfoDtoList)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "记录不存在，无法更新");
        }

        RssInfo rssInfo = new RssInfo();
        rssInfo.setId(updateRssRequestDto.getRssId());
        rssInfo.setRssName(updateRssRequestDto.getRssName());
        rssInfo.setDownloadPath(updateRssRequestDto.getDownloadPath());
        rssInfo.setRemark(updateRssRequestDto.getRemark());
        torrentDao.updateRssInfo(rssInfo);
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void removeRss(DeleteRssRequestDto deleteRssRequestDto) {
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();

        RssQueryRequestDto rssQueryRequestDto = new RssQueryRequestDto();
        rssQueryRequestDto.setCreatorId(loginUser.getUserId().toString());
        rssQueryRequestDto.setRssId(deleteRssRequestDto.getRssId());
        List<RssInfoDto> rssInfoDtoList = torrentDao.selectRssList(rssQueryRequestDto);
        //如果记录不存在，跳过
        if (CollectionUtils.isEmpty(rssInfoDtoList)) {
            return;
        }

        torrentDao.deleteRssInfo(deleteRssRequestDto.getRssId());
    }

    @Override
    public void updateNotCompletedTorrentState() {
        runWithScheduleLock("misu-file-server:torrent-state", stateUpdateLock, this::doUpdateNotCompletedTorrentState);
    }

    @Override
    public void moveCompletedTorrentToUserDirectory() {
        runWithScheduleLock("misu-file-server:torrent-file-sync", fileSyncLock, this::doMoveCompletedTorrentToUserDirectory);
    }

    private void doUpdateNotCompletedTorrentState() {
        //查询全部未完成的磁力链接
        TorrentInfoQueryRequestDto torrentInfoQueryRequestDto = new TorrentInfoQueryRequestDto();
        torrentInfoQueryRequestDto.setCompleteFlag(false);
        List<TorrentInfoDto> torrentInfoDtoList = torrentDao.selectTorrentInfoList(torrentInfoQueryRequestDto);
        if (CollectionUtils.isEmpty(torrentInfoDtoList)) {
            return;
        }

        //查询qBitTorrent内该部分torrent的下载状态
        String torrentHashes = torrentInfoDtoList.stream()
                .map(TorrentInfoDto::getTorrentHash)
                .collect(Collectors.joining("|"));
        TorrentInfoRequest torrentInfoRequest = new TorrentInfoRequest();
        torrentInfoRequest.setHashes(torrentHashes);
        List<TorrentInfoResponse> torrentList = qBitTorrentApi.getTorrentList(torrentInfoRequest);
        if (CollectionUtils.isNotEmpty(torrentList)) {
            //封装到对应的记录
            for (TorrentInfoResponse torrentInfoResponse : torrentList) {
                for (TorrentInfoDto torrentInfoDto : torrentInfoDtoList) {
                    if (torrentInfoDto.getTorrentHash().equals(torrentInfoResponse.getHash())) {
                        TorrentInfo torrentInfo = new TorrentInfo();
                        torrentInfo.setTorrentHash(torrentInfoDto.getTorrentHash());
                        if (TorrentInfoResponse.DOWNLOADING_STATE_LIST.contains(torrentInfoResponse.getState())) {
                            boolean needUpdate = false;
                            if (torrentInfoDto.getState() < 20 && torrentInfoDto.getState() != 10) {
                                //进度为20-下载中
                                torrentInfo.setState(20);
                                needUpdate = true;
                            }
                            if (StringUtils.isBlank(torrentInfoDto.getTorrentName())) {
                                //设置名称和大小
                                torrentInfo.setTorrentName(torrentInfoResponse.getName());
                                needUpdate = true;
                            }
                            if (torrentInfoDto.getTotalSize() == null) {
                                //设置名称和大小
                                torrentInfo.setTotalSize(torrentInfoResponse.getTotalSize());
                                needUpdate = true;
                            }

                            if (needUpdate) {
                                //执行更新
                                torrentDao.updateTorrentInfo(torrentInfo);
                            }
                        }else if (TorrentInfoResponse.COMPLETE_STATE_LIST.contains(torrentInfoResponse.getState())){
                            //进度为30-已完成
                            torrentInfo.setState(30);
                            if (StringUtils.isBlank(torrentInfoDto.getTorrentName())) {
                                //设置名称
                                torrentInfo.setTorrentName(torrentInfoResponse.getName());
                            }
                            if (torrentInfoDto.getTotalSize() == null) {
                                //设置大小
                                torrentInfo.setTotalSize(torrentInfoResponse.getTotalSize());
                            }
                            if (StringUtils.isBlank(torrentInfoDto.getDownloadPath())) {
                                //设置路径
                                torrentInfo.setDownloadPath(torrentInfoResponse.getSavePath());
                            }
                            //执行更新
                            torrentDao.updateTorrentInfo(torrentInfo);
                        }else if (TorrentInfoResponse.ERROR_STATE_LIST.contains(torrentInfoResponse.getState())) {
                            //进度为99-发生错误
                            torrentInfo.setState(99);
                            if ("missingFiles".equals(torrentInfoResponse.getState())) {
                                torrentInfo.setRemark("文件丢失");
                            }else {
                                torrentInfo.setRemark("下载失败");
                            }
                            //执行更新
                            torrentDao.updateTorrentInfo(torrentInfo);
                        }

                        break;
                    }
                }
            }
        }
    }

    private void doMoveCompletedTorrentToUserDirectory() {
        //查询已下载完毕但未同步到用户文件的记录
        UserTorrentQueryRequestDto userTorrentQueryRequestDto = new UserTorrentQueryRequestDto();
        userTorrentQueryRequestDto.setUserFileState(0);
        userTorrentQueryRequestDto.setServerFileState(30);
        List<UserTorrentDetailDto> userTorrentDetailDtoList = torrentDao.selectUserTorrent(userTorrentQueryRequestDto);

        //查询qBitTorrent内该部分torrent的下载状态
        if (CollectionUtils.isNotEmpty(userTorrentDetailDtoList)) {
            for (UserTorrentDetailDto userTorrentDetailDto : userTorrentDetailDtoList) {
                //获取路径，要去掉路径开头的/号
                String fileSubPath = userTorrentDetailDto.getServerDownloadPath().substring(1) + "/" + userTorrentDetailDto.getTorrentName();
                String localPath = qBitTorrentLocalPath + fileSubPath;
                File torrentFile = new File(localPath);
                //如果qBitTorrent服务端开启了远程
                if (remoteEnable) {
                    //判断是否已经从qBitTorrent同步到本地，如果没有则下载
                    boolean downloadSuccessFlag = judgeOrDownloadTorrentFile(userTorrentDetailDto, fileSubPath, localPath);
                    //如果没下载成功则跳过
                    if (!downloadSuccessFlag) {
                        continue;
                    }
                }

                try {
                    //调用文件系统添加映射
                    AddFileInkRequest addFileInkRequest = new AddFileInkRequest();
                    if ("public".equals(userTorrentDetailDto.getUserId())) {
                        addFileInkRequest.setOpenType(1);
                    }else {
                        addFileInkRequest.setOpenType(0);
                    }
                    addFileInkRequest.setFilePath(userTorrentDetailDto.getUserFilePath());
                    addFileInkRequest.setFileName(userTorrentDetailDto.getTorrentName());
                    addFileInkRequest.setUserId(userTorrentDetailDto.getUserId());
                    addFileInkRequest.setInkFilePath(torrentFile.getAbsolutePath());
                    fileService.addFileInk(addFileInkRequest);

                    //更新用户文件同步状态为已同步
                    TorrentUserRelation torrentUserRelation = new TorrentUserRelation();
                    torrentUserRelation.setId(userTorrentDetailDto.getUserTorrentId());
                    torrentUserRelation.setState(1);
                    torrentDao.updateTorrentUserRelation(torrentUserRelation);
                }catch (Exception e) {
                    String failedReason = null;
                    if (e instanceof ServiceException) {
                        failedReason = e.getMessage();
                    }else {
                        log.error("torrent下载的文件映射到用户失败，id：" + userTorrentDetailDto.getUserTorrentId(), e);
                        failedReason = "未知原因";
                    }

                    //更新用户文件同步状态为失败
                    TorrentUserRelation torrentUserRelation = new TorrentUserRelation();
                    torrentUserRelation.setId(userTorrentDetailDto.getUserTorrentId());
                    torrentUserRelation.setState(2);
                    torrentUserRelation.setFailedReason(failedReason);
                    torrentDao.updateTorrentUserRelation(torrentUserRelation);
                }
            }
        }
    }

    @Override
    public void updateRssStateSchedule() {
        runWithScheduleLock("misu-file-server:rss-state", rssUpdateLock, this::doUpdateRssStateSchedule);
    }

    private void doUpdateRssStateSchedule() {
        //查询全部rss订阅
        RssQueryRequestDto rssQueryRequestDto = new RssQueryRequestDto();
        List<RssInfoDto> rssInfoDtoList = torrentDao.selectRssList(rssQueryRequestDto);
        if (CollectionUtils.isEmpty(rssInfoDtoList)) {
            return;
        }

        for (RssInfoDto rssInfoDto : rssInfoDtoList) {
            refreshRssInternal(rssInfoDto, true, false);
        }
    }

    private void refreshRssInternal(RssInfoDto rssInfoDto, boolean autoDownload, boolean throwOnFailure) {
        try {
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.set("Accept", "application/xml");
            String xmlText = restUtils.get(rssInfoDto.getRssUrl(), httpHeaders, new TypeReference<String>() {});
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(new XmlReader(new ByteArrayInputStream(xmlText.getBytes(StandardCharsets.UTF_8))));
            List<RssRule> enabledRuleList = rssRuleRepository.findByRssIdAndEnabledTrueOrderByCreateTimeDesc(rssInfoDto.getId());
            for (SyndEntry entry : feed.getEntries()) {
                RssItem rssItem = saveOrUpdateRssItem(rssInfoDto.getId(), entry);
                RssRule matchedRule = matchRssRule(rssItem, enabledRuleList);
                rssItem.setMatchState(matchedRule == null ? 0 : 1);
                rssItem.setMatchedRuleId(matchedRule == null ? null : matchedRule.getId());
                rssItem.setUpdateTime(LocalDateTime.now());
                rssItemRepository.save(rssItem);
                if (autoDownload && matchedRule != null && Boolean.TRUE.equals(matchedRule.getAutoDownload())
                        && !Integer.valueOf(1).equals(rssItem.getDownloadState())) {
                    downloadRssItem(rssItem, rssInfoDto, matchedRule, rssInfoDto.getCreatorId());
                }
            }

            RssInfo rssInfo = new RssInfo();
            rssInfo.setId(rssInfoDto.getId());
            rssInfo.setState(1);
            rssInfo.setRemark(null);
            torrentDao.updateRssInfo(rssInfo);
        } catch (Exception e) {
            log.error("rss订阅链接：【{}】解析失败", rssInfoDto.getRssUrl(), e);
            RssInfo rssInfo = new RssInfo();
            rssInfo.setId(rssInfoDto.getId());
            rssInfo.setState(99);
            rssInfo.setRemark("订阅链接解析失败");
            torrentDao.updateRssInfo(rssInfo);
            if (throwOnFailure) {
                throw new ServiceException(HttpStatus.BAD_REQUEST, "订阅链接解析失败");
            }
        }
    }

    private RssItem saveOrUpdateRssItem(Long rssId, SyndEntry entry) {
        String torrentUrl = getTorrentUrl(entry);
        String torrentHash = getTorrentHashFromUrl(torrentUrl);
        String guid = StringUtils.firstNonBlank(entry.getUri(), entry.getLink(), torrentHash, entry.getTitle());
        Optional<RssItem> rssItemOptional = Optional.empty();
        if (StringUtils.isNotBlank(torrentHash)) {
            rssItemOptional = rssItemRepository.findFirstByRssIdAndTorrentHash(rssId, torrentHash);
        }
        if (rssItemOptional.isEmpty() && StringUtils.isNotBlank(guid)) {
            rssItemOptional = rssItemRepository.findFirstByRssIdAndGuid(rssId, guid);
        }
        RssItem rssItem = rssItemOptional.orElseGet(RssItem::new);
        if (rssItem.getId() == null) {
            rssItem.setRssId(rssId);
            rssItem.setMatchState(0);
            rssItem.setDownloadState(0);
            rssItem.setCreateTime(LocalDateTime.now());
        }
        rssItem.setGuid(guid);
        rssItem.setTitle(StringUtils.defaultIfBlank(entry.getTitle(), "未命名条目"));
        rssItem.setTorrentUrl(torrentUrl);
        rssItem.setTorrentHash(torrentHash);
        rssItem.setDescription(entry.getDescription() == null ? null : entry.getDescription().getValue());
        rssItem.setAuthor(entry.getAuthor());
        rssItem.setPublishTime(toLocalDateTime(entry.getPublishedDate()));
        rssItem.setUpdatedTime(toLocalDateTime(entry.getUpdatedDate()));
        rssItem.setUpdateTime(LocalDateTime.now());
        return rssItemRepository.save(rssItem);
    }

    private String getTorrentUrl(SyndEntry entry) {
        if (CollectionUtils.isNotEmpty(entry.getEnclosures())) {
            for (SyndEnclosure enclosure : entry.getEnclosures()) {
                if (StringUtils.isNotBlank(enclosure.getUrl())) {
                    return enclosure.getUrl();
                }
            }
        }
        if (StringUtils.startsWithIgnoreCase(entry.getLink(), "magnet:")) {
            return entry.getLink();
        }
        return null;
    }

    private LocalDateTime toLocalDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
    }

    private RssRule matchRssRule(RssItem rssItem, List<RssRule> rssRuleList) {
        if (CollectionUtils.isEmpty(rssRuleList)) {
            return null;
        }
        String content = StringUtils.defaultString(rssItem.getTitle()) + "\n" + StringUtils.defaultString(rssItem.getDescription());
        for (RssRule rssRule : rssRuleList) {
            if (matchKeywords(content, rssRule.getIncludeKeywords(), true)
                    && matchKeywords(content, rssRule.getExcludeKeywords(), false)
                    && matchRegex(content, rssRule.getRegex())) {
                return rssRule;
            }
        }
        return null;
    }

    private boolean matchKeywords(String content, String keywords, boolean requireAll) {
        if (StringUtils.isBlank(keywords)) {
            return true;
        }
        List<String> keywordList = Arrays.stream(keywords.split("[,，\\n]"))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .toList();
        if (CollectionUtils.isEmpty(keywordList)) {
            return true;
        }
        if (requireAll) {
            return keywordList.stream().allMatch(keyword -> StringUtils.containsIgnoreCase(content, keyword));
        }
        return keywordList.stream().noneMatch(keyword -> StringUtils.containsIgnoreCase(content, keyword));
    }

    private boolean matchRegex(String content, String regex) {
        if (StringUtils.isBlank(regex)) {
            return true;
        }
        try {
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(content).find();
        } catch (PatternSyntaxException e) {
            log.warn("RSS规则正则不合法：{}", regex, e);
            return false;
        }
    }

    private void downloadRssItem(RssItem rssItem, RssInfoDto rssInfoDto, RssRule matchedRule, String userId) {
        if (StringUtils.isBlank(rssItem.getTorrentUrl())) {
            markRssItemDownloadFailed(rssItem, "条目没有磁力链接");
            return;
        }
        try {
            AddTorrentRequestDto addTorrentRequestDto = new AddTorrentRequestDto();
            addTorrentRequestDto.setTorrentUrl(rssItem.getTorrentUrl());
            addTorrentRequestDto.setUserFilePath(matchedRule == null
                    ? rssInfoDto.getDownloadPath()
                    : StringUtils.defaultIfBlank(matchedRule.getDownloadPath(), rssInfoDto.getDownloadPath()));
            addUserTorrentForUser(addTorrentRequestDto, userId);
            rssItem.setDownloadState(1);
            rssItem.setErrorMessage(null);
            rssItem.setUpdateTime(LocalDateTime.now());
            rssItemRepository.save(rssItem);
        } catch (ServiceException e) {
            if (e.getCode() == HttpStatus.NOT_MODIFIED) {
                rssItem.setDownloadState(1);
                rssItem.setErrorMessage(null);
                rssItem.setUpdateTime(LocalDateTime.now());
                rssItemRepository.save(rssItem);
                return;
            }
            markRssItemDownloadFailed(rssItem, e.getMessage());
        } catch (Exception e) {
            log.error("RSS条目自动下载失败，itemId：{}", rssItem.getId(), e);
            markRssItemDownloadFailed(rssItem, "下载失败");
        }
    }

    private void markRssItemDownloadFailed(RssItem rssItem, String errorMessage) {
        rssItem.setDownloadState(2);
        rssItem.setErrorMessage(StringUtils.abbreviate(errorMessage, 200));
        rssItem.setUpdateTime(LocalDateTime.now());
        rssItemRepository.save(rssItem);
    }

    private void runWithScheduleLock(String lockName, ReentrantLock localLock, Runnable task) {
        if (!localLock.tryLock()) {
            return;
        }
        try (Connection connection = fileServerDataSource.getConnection()) {
            if (!tryAcquireDbLock(connection, lockName)) {
                return;
            }
            try {
                task.run();
            } finally {
                releaseDbLock(connection, lockName);
            }
        } catch (SQLException e) {
            log.error("获取定时任务数据库锁失败：{}", lockName, e);
        } finally {
            localLock.unlock();
        }
    }

    private boolean tryAcquireDbLock(Connection connection, String lockName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
            statement.setString(1, lockName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) == 1;
            }
        }
    }

    private void releaseDbLock(Connection connection, String lockName) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
            statement.setString(1, lockName);
            statement.executeQuery();
        } catch (SQLException e) {
            log.warn("释放定时任务数据库锁失败：{}", lockName, e);
        }
    }

    private boolean judgeOrDownloadTorrentFile(UserTorrentDetailDto userTorrentDetailDto, String remotePath, String localPath) {
        if (!remoteEnable) {
            return true;
        }

        File torrentFile = new File(localPath);
        //如果本地文件不存在，从qBitTorrent服务器下载
        if (!torrentFile.exists() || torrentFile.isDirectory()) {
            String remoteFullPath = qBitTorrentRemoteDownloadPath + remotePath;
            try {
                if (fileClientApi.isDirectory(remoteFullPath)) {
                    List<FileInfo> fileInfoList = fileClientApi.downloadDirectory(remoteFullPath);
                    for (FileInfo fileInfo : fileInfoList) {
                        //子文件路径
                        String remoteSubPath = fileInfo.getFilePath().replace(remoteFullPath, "") + "/" + fileInfo.getFileName();
                        //本地目录的子文件路径
                        String localSubPath = localPath + remoteSubPath;
                        //如果该文件已存在，跳过
                        if (new File(localSubPath).exists()) {
                            continue;
                        }
                        log.info("开始从qBitTorrent服务器下载目录子文件：{}", remotePath + remoteSubPath);
                        //下载文件到对应目录
                        downloadTorrentFile(fileInfo.getInputStream(), localSubPath);
                        log.info("从qBitTorrent服务器下载目录子文件【{}】完成", remotePath + remoteSubPath);
                    }
                }else {
                    log.info("开始从qBitTorrent服务器下载：{}", remotePath);
                    //下载文件到对应目录
                    downloadTorrentFile(fileClientApi.downloadFile(remoteFullPath), localPath);
                    log.info("从qBitTorrent服务器下载【{}】完成", remotePath);
                }
            }catch (Exception e) {
                log.error("下载qBitTorrent文件出现异常", e);
                String failedReason = "同步出现未知异常";
                if (e instanceof FileNotFoundException) {
                    failedReason = "服务器下载目录不存在文件";
                }
                //执行下载失败后置操作
                failedDownloadTorrentFileHandle(userTorrentDetailDto.getUserTorrentId(), localPath, failedReason);
                //返回失败标识
                return false;
            }
        }
        return true;
    }

    @SneakyThrows
    private void downloadTorrentFile(InputStream inputStream, String outputPath) {
        File outputFile = new File(outputPath);
        //如果父目录不存在则创建
        if (!outputFile.getParentFile().exists()) {
            outputFile.getParentFile().mkdirs();
        }
        try (FileOutputStream outputStream = new FileOutputStream(outputFile)){
            byte[] b = new byte[8192];
            int length;
            while ((length = inputStream.read(b)) > 0) {
                outputStream.write(b, 0, length);
            }
        }catch (Exception e) {
            //下载失败后删除输出的文件
            outputFile.delete();
            throw e;
        }finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }
    }

    private void failedDownloadTorrentFileHandle(Long userTorrentId, String torrentFilePath, String failedReason) {
        //删除下载失败的本地文件
        new File(torrentFilePath).delete();

        //更新用户文件同步状态为失败
        TorrentUserRelation torrentUserRelation = new TorrentUserRelation();
        torrentUserRelation.setId(userTorrentId);
        torrentUserRelation.setState(2);
        torrentUserRelation.setFailedReason(failedReason);
        torrentDao.updateTorrentUserRelation(torrentUserRelation);
    }
}
