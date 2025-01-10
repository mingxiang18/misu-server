package com.misu.fileServer.service.impl;

import com.alibaba.fastjson2.TypeReference;
import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.dao.TorrentDao;
import com.misu.fileServer.domain.dto.*;
import com.misu.fileServer.domain.entity.RssInfo;
import com.misu.fileServer.domain.entity.TorrentInfo;
import com.misu.fileServer.domain.entity.TorrentUserRelation;
import com.misu.fileServer.service.FileService;
import com.misu.fileServer.service.TorrentService;
import com.misu.fileServer.util.torrent.QBitTorrentApi;
import com.misu.fileServer.util.torrent.request.AddTorrentRequest;
import com.misu.fileServer.util.torrent.request.TorrentHashRequest;
import com.misu.fileServer.util.torrent.request.TorrentInfoRequest;
import com.misu.fileServer.util.torrent.response.TorrentInfoResponse;
import com.misu.framework.config.file.FilePathConfig;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 磁力相关Service
 *
 * @author misu
 */
@Slf4j
@Service
public class TorrentServiceImpl implements TorrentService {

    private final static String TORRENT_DIRECTORY = "torrent/";

    @Resource
    private TorrentDao torrentDao;

    @Resource
    private QBitTorrentApi qBitTorrentApi;

    @Resource
    private FilePathConfig filePathConfig;

    @Resource
    private FileClientApi fileClientApi;

    @Resource
    private FileService fileService;

    @Resource
    private RestUtils restUtils;

    @Value("${qBitTorrent.downloadPath}")
    private String qBitTorrentDownloadPath;

    /**
     * 定时状态更新锁
     */
    private final ReentrantLock stateUpdateLock = new ReentrantLock();
    /**
     * 文件同步锁
     */
    private final ReentrantLock fileSyncLock = new ReentrantLock();

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
                                //设置进度和下载速率
                                userTorrentDetailDto.setTorrentName(torrentInfoResponse.getName());
                                userTorrentDetailDto.setTotalSize(torrentInfoResponse.getTotalSize());
                                userTorrentDetailDto.setServerFileProgress(torrentInfoResponse.getProgress());
                                userTorrentDetailDto.setServerFileDownloadSpeed(torrentInfoResponse.getDlSpeed());
                                userTorrentDetailDto.setTorrentState(torrentInfoResponse.getState());
                                //如果进度为1，则设置为完成
                                if (torrentInfoResponse.getProgress() >= 1) {
                                    userTorrentDetailDto.setServerFileState(30);
                                }
                            }
                        });
                    }
                }
            }catch (Exception e) {
                log.error("查询qBitTorrent获取torrent状态列表失败", e);
            }
        }

        return userTorrentDetailDtoList;
    }

    @Override
    public Page<RssInfoDto> getRssList(RssQueryRequestDto rssQueryRequestDto) {
        rssQueryRequestDto.setCreatorId(LoginMessageUtil.getLoginUser().get().getUserId().toString());
        return torrentDao.selectRssListByPage(rssQueryRequestDto, PageUtils.getPageRequest());
    }

    @Override
    public RssDetailDto getRssDetail(RssDetailRequestDto rssDetailRequestDto) {
        RssQueryRequestDto rssQueryRequestDto = new RssQueryRequestDto();
        rssQueryRequestDto.setRssId(rssDetailRequestDto.getRssId());
        rssQueryRequestDto.setCreatorId(LoginMessageUtil.getLoginUser().get().getUserId().toString());
        List<RssInfoDto> rssInfoDtoList = torrentDao.selectRssList(rssQueryRequestDto);
        if (CollectionUtils.isEmpty(rssInfoDtoList)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "记录不存在");
        }

        RssInfoDto rssInfoDto = rssInfoDtoList.get(0);

        RssDetailDto rssDetailDto = new RssDetailDto();
        rssDetailDto.setRssUrl(rssInfoDto.getRssUrl());
        rssDetailDto.setRssName(rssInfoDto.getRssName());
        rssDetailDto.setDownloadPath(rssInfoDto.getDownloadPath());
        rssDetailDto.setState(rssInfoDto.getState());
        rssDetailDto.setRemark(rssInfoDto.getRemark());

        List<RssTorrentRelationDto> rssTorrentRelationDtoList = new ArrayList<>();
        try {
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.set("Accept", "application/xml");
            String xmlText = restUtils.get(rssInfoDto.getRssUrl(), httpHeaders, new TypeReference<String>() {});
            // 解析RSS源
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(new XmlReader(new ByteArrayInputStream(xmlText.getBytes(StandardCharsets.UTF_8))));
            //获取全部节点
            List<SyndEntry> entries = feed.getEntries();
            for (SyndEntry entry : entries) {
                RssTorrentRelationDto rssTorrentRelationDto = new RssTorrentRelationDto();
                rssTorrentRelationDto.setTitle(entry.getTitle());
                //磁力链接获取
                List<SyndEnclosure> enclosures = entry.getEnclosures();
                for (SyndEnclosure enclosure : enclosures) {
                    rssTorrentRelationDto.setTorrentUrl(enclosure.getUrl());
                }
                rssTorrentRelationDto.setTorrentHash(getTorrentHashFromUrl(rssTorrentRelationDto.getTorrentUrl()));
                rssTorrentRelationDto.setDescription(entry.getDescription().getValue());
                rssTorrentRelationDto.setPublishDate(entry.getPublishedDate());
                rssTorrentRelationDto.setUpdatedDate(entry.getUpdatedDate());
                rssTorrentRelationDto.setAuthor(entry.getAuthor());
                rssTorrentRelationDtoList.add(rssTorrentRelationDto);
            }
        }catch (Exception e) {
            log.error("rss订阅链接：【{}】解析失败", rssInfoDto.getRssUrl());
            rssDetailDto.setState(99);
        }

        //遍历订阅链接的所有数据，判断每条记录是否存在用户下载
        if (CollectionUtils.isNotEmpty(rssTorrentRelationDtoList)) {
            UserTorrentQueryRequestDto userTorrentQueryRequestDto = new UserTorrentQueryRequestDto();
            userTorrentQueryRequestDto.setUserId(LoginMessageUtil.getLoginUser().get().getUserId().toString());
            userTorrentQueryRequestDto.setUserFilePath(rssInfoDto.getDownloadPath());
            userTorrentQueryRequestDto.setTorrentHashList(rssTorrentRelationDtoList.stream()
                    .map(RssTorrentRelationDto::getTorrentHash)
                    .filter(StringUtils::isNotEmpty)
                    .distinct()
                    .collect(Collectors.toList()));
            List<UserTorrentDetailDto> userTorrentDetailDtoList = torrentDao.selectUserTorrent(userTorrentQueryRequestDto);

            //封装查询出的记录
            Set<String> userTorrentHashSet = userTorrentDetailDtoList.stream()
                    .map(UserTorrentDetailDto::getTorrentHash)
                    .collect(Collectors.toSet());
            for (RssTorrentRelationDto rssTorrentRelationDto : rssTorrentRelationDtoList) {
                if (userTorrentHashSet.contains(rssTorrentRelationDto.getTorrentHash())) {
                    rssTorrentRelationDto.setDownloadState(1);
                }else {
                    rssTorrentRelationDto.setDownloadState(0);
                }
            }
        }


        rssDetailDto.setRssTorrentRelationList(rssTorrentRelationDtoList);
        return rssDetailDto;
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void addUserTorrent(AddTorrentRequestDto addTorrentRequestDto) {
        String torrentHash = getTorrentHashFromUrl(addTorrentRequestDto.getTorrentUrl());

        //数据库获取该磁力链接数据
        Optional<TorrentInfo> torrentInfoOptional = torrentDao.selectTorrentInfoByHash(torrentHash);
        TorrentInfo torrentInfo = null;

        //如果不存在，则保存到数据库
        if (torrentInfoOptional.isEmpty()) {
            torrentInfo = new TorrentInfo();
            torrentInfo.setTorrentHash(torrentHash);
            torrentInfo.setTorrentUrl(addTorrentRequestDto.getTorrentUrl());
            torrentInfo.setState(0);
            torrentInfo.setCreatorId(LoginMessageUtil.getLoginUser().get().getUserId().toString());
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

        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
        //查询用户和磁力链接是否有关联
        UserTorrentQueryRequestDto userTorrentQueryRequestDto = new UserTorrentQueryRequestDto();
        userTorrentQueryRequestDto.setUserId(loginUser.getUserId().toString());
        userTorrentQueryRequestDto.setTorrentHash(torrentHash);
        userTorrentQueryRequestDto.setUserFilePath(addTorrentRequestDto.getUserFilePath());
        userTorrentQueryRequestDto.setUserFileState(0);
        List<UserTorrentDetailDto> userTorrentList = torrentDao.selectUserTorrent(userTorrentQueryRequestDto);
        if (CollectionUtils.isEmpty(userTorrentList)) {
            TorrentUserRelation torrentUserRelation = new TorrentUserRelation();
            torrentUserRelation.setUserId(loginUser.getUserId().toString());
            torrentUserRelation.setUserFilePath(addTorrentRequestDto.getUserFilePath());
            torrentUserRelation.setTorrentHash(torrentHash);
            torrentUserRelation.setState(0);
            torrentUserRelation.setCreateTime(LocalDateTime.now());
            torrentDao.saveTorrentUserRelation(torrentUserRelation);
        }else {
            throw new ServiceException(HttpStatus.NOT_MODIFIED, "当前目录已存在该磁力链接的下载记录，请勿重复添加");
        }
    }

    /**
     * 从magnet磁力链接中获取hash值
     */
    private static String getTorrentHashFromUrl(String torrentUrl) {
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
        //判断上次状态更新是否结束，如果结束则加锁开始执行新的状态更新，否则跳过该次状态更新
        if (stateUpdateLock.tryLock()) {
            try {
                //查询全部未完成的磁力链接
                TorrentInfoQueryRequestDto torrentInfoQueryRequestDto = new TorrentInfoQueryRequestDto();
                torrentInfoQueryRequestDto.setCompleteFlag(false);
                List<TorrentInfoDto> torrentInfoDtoList = torrentDao.selectTorrentInfoList(torrentInfoQueryRequestDto);

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
                                        //设置名称和大小
                                        torrentInfo.setTorrentName(torrentInfoResponse.getName());
                                    }
                                    if (torrentInfoDto.getTotalSize() == null) {
                                        //设置名称和大小
                                        torrentInfo.setTotalSize(torrentInfoResponse.getTotalSize());
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
            }finally {
                stateUpdateLock.unlock();
            }
        }
    }

    @Override
    public void moveCompletedTorrentToUserDirectory() {
        //判断上次文件同步是否结束，如果结束则加锁开始执行新的同步，否则跳过该次同步
        if (fileSyncLock.tryLock()) {
            try {
                //查询已下载完毕但未同步到用户文件的记录
                UserTorrentQueryRequestDto userTorrentQueryRequestDto = new UserTorrentQueryRequestDto();
                userTorrentQueryRequestDto.setUserFileState(0);
                userTorrentQueryRequestDto.setServerFileState(30);
                List<UserTorrentDetailDto> userTorrentDetailDtoList = torrentDao.selectUserTorrent(userTorrentQueryRequestDto);

                //查询qBitTorrent内该部分torrent的下载状态
                String torrentHashes = userTorrentDetailDtoList.stream()
                        .map(UserTorrentDetailDto::getTorrentHash)
                        .collect(Collectors.joining("|"));
                TorrentInfoRequest torrentInfoRequest = new TorrentInfoRequest();
                torrentInfoRequest.setHashes(torrentHashes);
                List<TorrentInfoResponse> torrentList = qBitTorrentApi.getTorrentList(torrentInfoRequest);
                if (CollectionUtils.isNotEmpty(torrentList)) {
                    for (UserTorrentDetailDto userTorrentDetailDto : userTorrentDetailDtoList) {
                        for (TorrentInfoResponse torrentInfoResponse : torrentList) {
                            if (torrentInfoResponse.getHash().equals(userTorrentDetailDto.getTorrentHash())) {
                                //获取路径，要去掉路径开头的/号
                                String remotePath = torrentInfoResponse.getContentPath().substring(1);
                                String localPath = filePathConfig.getFilePath() + TORRENT_DIRECTORY + remotePath;
                                File torrentFile = new File(localPath);
                                //判断是否已经从qBitTorrent同步到本地，如果没有则下载
                                boolean downloadSuccessFlag = judgeOrDownloadTorrentFile(userTorrentDetailDto, remotePath, localPath);
                                //如果没下载成功则跳出循环
                                if (!downloadSuccessFlag) {
                                    break;
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
                                    addFileInkRequest.setFileName(torrentInfoResponse.getName());
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

                                break;
                            }
                        }
                    }

                }
            }finally {
                fileSyncLock.unlock();
            }
        }
    }

    @Override
    public void updateRssStateSchedule() {
        //查询全部rss订阅
        RssQueryRequestDto rssQueryRequestDto = new RssQueryRequestDto();
        rssQueryRequestDto.setState(0);
        List<RssInfoDto> rssInfoDtoList = torrentDao.selectRssList(rssQueryRequestDto);
        if (CollectionUtils.isEmpty(rssInfoDtoList)) {
            return;
        }

        for (RssInfoDto rssInfoDto : rssInfoDtoList) {
            try {
                HttpHeaders httpHeaders = new HttpHeaders();
                httpHeaders.set("Accept", "application/xml");
                String xmlText = restUtils.get(rssInfoDto.getRssUrl(), httpHeaders, new TypeReference<String>() {});
                // 解析RSS源
                SyndFeedInput input = new SyndFeedInput();
                SyndFeed feed = input.build(new XmlReader(new ByteArrayInputStream(xmlText.getBytes(StandardCharsets.UTF_8))));

                RssInfo rssInfo = new RssInfo();
                rssInfo.setId(rssInfoDto.getId());
                rssInfo.setState(1);
                torrentDao.updateRssInfo(rssInfo);
            } catch (Exception e) {
                log.error("rss订阅链接：【{}】解析失败", rssInfoDto.getRssUrl());
                RssInfo rssInfo = new RssInfo();
                rssInfo.setId(rssInfoDto.getId());
                rssInfo.setState(99);
                rssInfo.setRemark("订阅链接解析失败");
                torrentDao.updateRssInfo(rssInfo);
            }
        }
    }

    private boolean judgeOrDownloadTorrentFile(UserTorrentDetailDto userTorrentDetailDto, String remotePath, String localPath) {
        File torrentFile = new File(localPath);
        //如果本地文件不存在，从qBitTorrent服务器下载
        if (!torrentFile.exists() || torrentFile.isDirectory()) {
            String remoteFullPath = qBitTorrentDownloadPath + remotePath;
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
