package com.misu.fileServer.service.impl;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.common.util.CacheUtils;
import com.misu.fileServer.dao.VideoRoomDao;
import com.misu.fileServer.domain.dto.*;
import com.misu.fileServer.domain.entity.VideoRoom;
import com.misu.fileServer.domain.entity.VideoRoomEvent;
import com.misu.fileServer.repository.VideoRoomEventRepository;
import com.misu.fileServer.service.FileService;
import com.misu.fileServer.service.VideoRoomService;
import com.misu.fileServer.util.FilePathGuard;
import com.misu.security.dto.LoginUser;
import com.misu.security.utils.LoginMessageUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 放映室相关Service
 *
 * @author misu
 */
@Slf4j
@Service
public class VideoRoomServiceImpl implements VideoRoomService {

    @Resource
    private VideoRoomDao videoRoomDao;

    @Resource
    private FileService fileService;

    @Resource
    private VideoRoomEventRepository videoRoomEventRepository;

    private final static String VIDEO_STATE_KEY = "video-room-state:";

    private final static String VIDEO_ROOM_USER_KEY = "video-room-user:";

    @Override
    public VideoRoomDto getVideoRoomFromId(String roomId) {
        Optional<VideoRoom> videoRoomOptional = videoRoomDao.selectByRoomId(roomId);

        if (videoRoomOptional.isPresent()) {
            VideoRoom videoRoom = videoRoomOptional.get();
            VideoRoomDto videoRoomDto = convertToRoomDto(videoRoom);
            //判断当前用户是否为放映室创建者
            if (videoRoom.getCreatorId().equals(LoginMessageUtil.getLoginUser().get().getUserId().toString())) {
                videoRoomDto.setCreatorFlag(true);
            }

            return videoRoomDto;
        }else {
            throw new ServiceException(HttpStatus.NOT_FOUND, "放映室不存在或已关闭");
        }
    }

    @Override
    public VideoStateInRoomDto getVideoState(String roomId) {
        String cacheKey = VIDEO_STATE_KEY + roomId;
        //从缓存获取
        VideoStateInRoomDto videoStateInRoomDto = CacheUtils.getCacheObject(cacheKey);

        //如果为空，从数据库获取
        if (videoStateInRoomDto == null) {
            Optional<VideoRoom> videoRoomOptional = videoRoomDao.selectByRoomId(roomId);
            if (videoRoomOptional.isPresent()) {
                VideoRoom videoRoom = videoRoomOptional.get();

                videoStateInRoomDto = new VideoStateInRoomDto();
                videoStateInRoomDto.setRoomId(videoRoom.getRoomId());
                videoStateInRoomDto.setRoomName(videoRoom.getRoomName());
                videoStateInRoomDto.setDirectoryOpenFlag(videoRoom.getDirectoryOpenFlag());
                videoStateInRoomDto.setDirectoryPath(videoRoom.getDirectoryPath());
                videoStateInRoomDto.setVideoPath(getPlaybackVideoPath(videoRoom));
                videoStateInRoomDto.setState(videoRoom.getState());
                videoStateInRoomDto.setSyncTime(videoRoom.getSyncTime());
                videoStateInRoomDto.setVideoTime(videoRoom.getVideoTime());
                CacheUtils.setCacheObject(cacheKey, videoStateInRoomDto, 10, ChronoUnit.MINUTES);
            }else {
                throw new ServiceException(HttpStatus.NOT_FOUND, "放映室不存在或已关闭");
            }
        }
        
        if ("play".equals(videoStateInRoomDto.getState()) && LocalDateTime.now().isBefore(videoStateInRoomDto.getSyncTime().plusMinutes(5))) {
            //如果视频当前是播放状态，且房主上次同步时间在5分钟内，则说明房主在线，计算SyncTime与当前时间的偏移量，添加到videoTime中
            long offset = ChronoUnit.SECONDS.between(videoStateInRoomDto.getSyncTime(), LocalDateTime.now());
            videoStateInRoomDto.setPlayTime(videoStateInRoomDto.getVideoTime().plusSeconds(offset));
        }else {
            //如果不是正在播放状态则直接设置上次同步的播放进度，且播放状态为暂停
            videoStateInRoomDto.setPlayTime(videoStateInRoomDto.getVideoTime());
            videoStateInRoomDto.setState("pause");
        }

        //将当前用户添加到观众列表
        addToRoomUser(roomId);
        //获取观众列表
        videoStateInRoomDto.setVideoRoomUserList(getRoomUserList(videoStateInRoomDto.getRoomId()));

        return videoStateInRoomDto;
    }

    @Override
    public String getVideoRoomShareUrl(String roomId) {
        //前端相对路径
        return "fileServer/videoRoom/" + roomId;
    }

    @Override
    public List<VideoRoomUserDto> getRoomMembers(String roomId) {
        assertRoomExists(roomId);
        addToRoomUser(roomId);
        return getRoomUserList(roomId);
    }

    @Override
    public void sendComment(SendVideoRoomCommentRequestDto requestDto) {
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
        recordCommentEvent(requestDto.getRoomId(), loginUser, requestDto.getContent(), System.currentTimeMillis());
    }

    @Override
    public List<VideoRoomCommentDto> getComments(String roomId) {
        assertRoomExists(roomId);
        addToRoomUser(roomId);
        List<VideoRoomEvent> eventList = videoRoomEventRepository.findTop100ByRoomIdAndEventTypeOrderByCreateTimeDescIdDesc(roomId, "COMMENT");
        Collections.reverse(eventList);
        return eventList.stream()
                .map(this::convertToCommentDto)
                .toList();
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public VideoRoomEventDto recordPlaybackEvent(String roomId, LoginUser loginUser, String state, Long videoTimeSeconds,
                                                 Long clientSendTime, String payload) {
        VideoRoom videoRoom = assertRoomExists(roomId);
        if (!videoRoom.getCreatorId().equals(String.valueOf(loginUser.getUserId()))) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "您不是当前放映室房主，无权限修改进度");
        }
        if (!"play".equals(state) && !"pause".equals(state)) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "播放状态不合法");
        }

        long safeVideoTimeSeconds = Math.max(videoTimeSeconds == null ? 0L : videoTimeSeconds, 0L);
        LocalTime videoTime = LocalTime.ofSecondOfDay(safeVideoTimeSeconds % (24 * 60 * 60));
        videoRoom.setState(state);
        videoRoom.setVideoTime(videoTime);
        videoRoom.setSyncTime(LocalDateTime.now());
        videoRoomDao.updateById(videoRoom);

        String cacheKey = VIDEO_STATE_KEY + roomId;
        VideoStateInRoomDto videoStateInRoomDto = new VideoStateInRoomDto();
        videoStateInRoomDto.setRoomId(videoRoom.getRoomId());
        videoStateInRoomDto.setRoomName(videoRoom.getRoomName());
        videoStateInRoomDto.setDirectoryOpenFlag(videoRoom.getDirectoryOpenFlag());
        videoStateInRoomDto.setDirectoryPath(videoRoom.getDirectoryPath());
        videoStateInRoomDto.setVideoPath(getPlaybackVideoPath(videoRoom));
        videoStateInRoomDto.setState(videoRoom.getState());
        videoStateInRoomDto.setSyncTime(videoRoom.getSyncTime());
        videoStateInRoomDto.setVideoTime(videoRoom.getVideoTime());
        CacheUtils.setCacheObject(cacheKey, videoStateInRoomDto, 10, ChronoUnit.MINUTES);

        addToRoomUser(roomId, loginUser);
        VideoRoomEventDto eventDto = saveRoomEvent(roomId, "PLAYBACK", loginUser, state, safeVideoTimeSeconds,
                clientSendTime, null, payload);
        fillRoomSnapshot(eventDto, videoRoom);
        return eventDto;
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public VideoRoomEventDto recordCommentEvent(String roomId, LoginUser loginUser, String content, Long clientSendTime) {
        assertRoomExists(roomId);
        addToRoomUser(roomId, loginUser);
        return saveRoomEvent(roomId, "COMMENT", loginUser, null, null, clientSendTime,
                StringUtils.trim(content), null);
    }

    @Override
    public VideoRoomDto getMyActiveRoom() {
        Optional<LoginUser> loginUserOptional = LoginMessageUtil.getLoginUser();
        if (loginUserOptional.isEmpty()) {
            return null;
        }
        return videoRoomDao.selectOneByCreatorId(String.valueOf(loginUserOptional.get().getUserId()))
                .map(this::convertToRoomDto)
                .orElse(null);
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public VideoRoomDto playMyVideo(PlayMyVideoRequestDto requestDto) {
        Optional<LoginUser> loginUserOptional = LoginMessageUtil.getLoginUser();
        if (loginUserOptional.isEmpty()) {
            return null;
        }

        LoginUser loginUser = loginUserOptional.get();
        validatePlayMyVideoRequest(requestDto, loginUser);
        String videoPath = buildInternalVideoPath(requestDto.getDirectoryOpenFlag(), requestDto.getFilePath(),
                Boolean.TRUE.equals(requestDto.getPreferTranscoded()));

        Optional<VideoRoom> videoRoomOptional = videoRoomDao.selectOneByCreatorId(loginUser.getUserId().toString());
        if (videoRoomOptional.isEmpty()) {
            VideoRoom videoRoom = new VideoRoom();
            videoRoom.setRoomId(UUID.randomUUID().toString());
            videoRoom.setRoomName(requestDto.getRoomName());
            videoRoom.setDirectoryOpenFlag(requestDto.getDirectoryOpenFlag());
            videoRoom.setDirectoryPath(extractDirectoryPath(requestDto.getFilePath()));
            videoRoom.setVideoPath(videoPath);
            videoRoom.setCreatorId(String.valueOf(loginUser.getUserId()));
            videoRoom.setCreateTime(LocalDateTime.now());
            videoRoom.setState("pause");
            videoRoom.setVideoTime(LocalTime.of(0, 0, 0));
            videoRoom.setSyncTime(LocalDateTime.now());
            videoRoom.setExpireTime(LocalDateTime.now().plusHours(12));
            videoRoomDao.save(videoRoom);
            refreshVideoStateCache(videoRoom);
            return convertToRoomDto(videoRoom);
        } else {
            VideoRoom videoRoom = videoRoomOptional.get();
            videoRoom.setRoomName(requestDto.getRoomName());
            videoRoom.setDirectoryOpenFlag(requestDto.getDirectoryOpenFlag());
            videoRoom.setDirectoryPath(extractDirectoryPath(requestDto.getFilePath()));
            videoRoom.setVideoPath(videoPath);
            videoRoom.setState("pause");
            videoRoom.setVideoTime(LocalTime.of(0, 0, 0));
            videoRoom.setSyncTime(LocalDateTime.now());
            videoRoom.setExpireTime(LocalDateTime.now().plusHours(12));
            videoRoomDao.updateById(videoRoom);
            refreshVideoStateCache(videoRoom);
            return convertToRoomDto(videoRoom);
        }
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void updateVideoState(UpdateVideoStateRequestDto updateVideoStateRequestDto) {
        //获取登陆用户
        Optional<LoginUser> loginUserOptional = LoginMessageUtil.getLoginUser();

        //获取播放室所属用户
        Optional<VideoRoom> videoRoomOptional = videoRoomDao.selectByRoomId(updateVideoStateRequestDto.getRoomId());
        if (videoRoomOptional.isEmpty()) {
            throw new ServiceException(HttpStatus.NOT_FOUND, "放映室不存在或已关闭");
        }

        VideoRoom videoRoom = videoRoomOptional.get();
        String creatorId = videoRoom.getCreatorId();

        //判断是否为所属用户
        if (!creatorId.equals(String.valueOf(loginUserOptional.get().getUserId()))) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "您不是当前放映室房主，无权限修改进度");
        }

        //更新播放状态
        videoRoom.setState(updateVideoStateRequestDto.getState());
        videoRoom.setVideoTime(updateVideoStateRequestDto.getVideoTime());
        videoRoom.setSyncTime(LocalDateTime.now());
        videoRoomDao.updateById(videoRoom);

        //更新缓存
        String cacheKey = VIDEO_STATE_KEY + updateVideoStateRequestDto.getRoomId();
        VideoStateInRoomDto videoStateInRoomDto = new VideoStateInRoomDto();
        videoStateInRoomDto.setRoomId(videoRoom.getRoomId());
        videoStateInRoomDto.setRoomName(videoRoom.getRoomName());
        videoStateInRoomDto.setDirectoryOpenFlag(videoRoom.getDirectoryOpenFlag());
        videoStateInRoomDto.setDirectoryPath(videoRoom.getDirectoryPath());
        videoStateInRoomDto.setVideoPath(getPlaybackVideoPath(videoRoom));
        videoStateInRoomDto.setState(videoRoom.getState());
        videoStateInRoomDto.setSyncTime(videoRoom.getSyncTime());
        videoStateInRoomDto.setVideoTime(videoRoom.getVideoTime());
        CacheUtils.setCacheObject(cacheKey, videoStateInRoomDto, 10, ChronoUnit.MINUTES);

        //将当前用户添加到观众列表
        addToRoomUser(videoRoom.getRoomId());
    }

    @Override
    public void quitVideoRoom(VideoRoomRequestDto videoRoomRequestDto) {
        //将当前用户从观众列表移除
        removeFromRoomUser(videoRoomRequestDto.getRoomId());
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void closeVideoRoom(VideoRoomRequestDto videoRoomRequestDto) {
        //获取登陆用户
        Optional<LoginUser> loginUserOptional = LoginMessageUtil.getLoginUser();

        //获取播放室所属用户
        Optional<VideoRoom> videoRoomOptional = videoRoomDao.selectByRoomId(videoRoomRequestDto.getRoomId());
        if (videoRoomOptional.isEmpty()) {
            return;
        }

        VideoRoom videoRoom = videoRoomOptional.get();
        String creatorId = videoRoom.getCreatorId();

        //判断是否为所属用户
        if (!creatorId.equals(String.valueOf(loginUserOptional.get().getUserId()))) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "您不是当前放映室房主，无权限修改进度");
        }

        //更新房间过期时间为已过期
        videoRoom.setExpireTime(LocalDateTime.now().minusMinutes(1));
        videoRoomDao.updateById(videoRoom);

        //删除缓存
        String roomUserKey = VIDEO_ROOM_USER_KEY + videoRoomRequestDto.getRoomId();
        String videoStateKey = VIDEO_STATE_KEY + videoRoomRequestDto.getRoomId();
        CacheUtils.removeCacheObject(roomUserKey);
        CacheUtils.removeCacheObject(videoStateKey);
    }

    @Override
    public void streamRoomVideo(String roomId, HttpServletRequest request, HttpServletResponse response) {
        VideoRoom videoRoom = assertRoomExists(roomId);
        InternalFileVideoPath internalPath = parseInternalFileVideoPath(videoRoom.getVideoPath());
        if (internalPath == null) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "当前放映室视频不是内部文件");
        }
        String ownerUserId = internalPath.openType() == 1 ? "public" : videoRoom.getCreatorId();
        if ("transcodedVideo".equals(internalPath.action())) {
            fileService.transcodedVideoFileAsUser(internalPath.openType(), ownerUserId, internalPath.filePath(), request, response);
        } else {
            fileService.accessUserFileAsUser(internalPath.openType(), ownerUserId, internalPath.filePath(), request, response, false);
        }
    }

    private VideoRoomDto convertToRoomDto(VideoRoom videoRoom) {
        VideoRoomDto videoRoomDto = new VideoRoomDto();
        videoRoomDto.setRoomId(videoRoom.getRoomId());
        videoRoomDto.setRoomName(videoRoom.getRoomName());
        videoRoomDto.setDirectoryPath(videoRoom.getDirectoryPath());
        videoRoomDto.setDirectoryOpenFlag(videoRoom.getDirectoryOpenFlag());
        videoRoomDto.setVideoPath(getPlaybackVideoPath(videoRoom));
        videoRoomDto.setCreatorId(videoRoom.getCreatorId());
        videoRoomDto.setCreateTime(videoRoom.getCreateTime());
        return videoRoomDto;
    }

    private void refreshVideoStateCache(VideoRoom videoRoom) {
        VideoStateInRoomDto videoStateInRoomDto = new VideoStateInRoomDto();
        videoStateInRoomDto.setRoomId(videoRoom.getRoomId());
        videoStateInRoomDto.setRoomName(videoRoom.getRoomName());
        videoStateInRoomDto.setDirectoryOpenFlag(videoRoom.getDirectoryOpenFlag());
        videoStateInRoomDto.setDirectoryPath(videoRoom.getDirectoryPath());
        videoStateInRoomDto.setVideoPath(getPlaybackVideoPath(videoRoom));
        videoStateInRoomDto.setState(videoRoom.getState());
        videoStateInRoomDto.setSyncTime(videoRoom.getSyncTime());
        videoStateInRoomDto.setVideoTime(videoRoom.getVideoTime());
        CacheUtils.setCacheObject(VIDEO_STATE_KEY + videoRoom.getRoomId(), videoStateInRoomDto, 10, ChronoUnit.MINUTES);
    }

    private void fillRoomSnapshot(VideoRoomEventDto eventDto, VideoRoom videoRoom) {
        eventDto.setRoomName(videoRoom.getRoomName());
        eventDto.setDirectoryOpenFlag(videoRoom.getDirectoryOpenFlag());
        eventDto.setDirectoryPath(videoRoom.getDirectoryPath());
        eventDto.setVideoPath(getPlaybackVideoPath(videoRoom));
    }

    private String getPlaybackVideoPath(VideoRoom videoRoom) {
        if (parseInternalFileVideoPath(videoRoom.getVideoPath()) == null) {
            return videoRoom.getVideoPath();
        }
        String videoKey = Integer.toHexString(StringUtils.defaultString(videoRoom.getVideoPath()).hashCode());
        return "fileServer/videoRoom/video?roomId=" + videoRoom.getRoomId() + "&videoKey=" + videoKey;
    }

    private InternalFileVideoPath parseInternalFileVideoPath(String videoPath) {
        if (StringUtils.isBlank(videoPath)) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(videoPath);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String path = StringUtils.defaultString(uri.getPath());
        if (!path.endsWith("/file/stream") && !path.endsWith("/file/transcodedVideo")) {
            return null;
        }
        Map<String, String> queryParams = parseQueryParams(uri.getRawQuery());
        String openTypeValue = queryParams.get("openType");
        String filePath = queryParams.get("filePath");
        if (StringUtils.isBlank(openTypeValue) || StringUtils.isBlank(filePath)) {
            return null;
        }
        try {
            int openType = Integer.parseInt(openTypeValue);
            String action = path.endsWith("/file/transcodedVideo") ? "transcodedVideo" : "stream";
            return new InternalFileVideoPath(openType, filePath, action);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, String> parseQueryParams(String rawQuery) {
        Map<String, String> queryParams = new HashMap<>();
        if (StringUtils.isBlank(rawQuery)) {
            return queryParams;
        }
        for (String pair : rawQuery.split("&")) {
            int separatorIndex = pair.indexOf('=');
            if (separatorIndex <= 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, separatorIndex), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(separatorIndex + 1), StandardCharsets.UTF_8);
            queryParams.put(key, value);
        }
        return queryParams;
    }

    private record InternalFileVideoPath(Integer openType, String filePath, String action) {
    }

    /**
     * 添加当前登陆账号到房间用户列表
     */
    private void addToRoomUser(String roomId) {
        Optional<LoginUser> loginUserOptional = LoginMessageUtil.getLoginUser();
        loginUserOptional.ifPresent(loginUser -> addToRoomUser(roomId, loginUser));
    }

    /**
     * 添加指定账号到房间用户列表
     */
    private void addToRoomUser(String roomId, LoginUser loginUser) {
        String roomUserKey = VIDEO_ROOM_USER_KEY + roomId;
        Map<Long, VideoRoomUserDto> roomUserMap = CacheUtils.getCacheObject(roomUserKey);
        if (roomUserMap == null) {
            roomUserMap = new HashMap<>();
            //10分钟后过期
            CacheUtils.setCacheObject(roomUserKey, roomUserMap, 10, ChronoUnit.MINUTES);
        }

        roomUserMap.put(loginUser.getUserId(), new VideoRoomUserDto(loginUser.getUserName(), LocalDateTime.now()));
    }

    /**
     * 获取当前房间用户列表
     */
    private List<VideoRoomUserDto> getRoomUserList(String roomId) {
        String roomUserKey = VIDEO_ROOM_USER_KEY + roomId;
        Map<Long, VideoRoomUserDto> roomUserMap = CacheUtils.getCacheObject(roomUserKey);
        if (roomUserMap == null) {
            return new ArrayList<>();
        }else {
            return roomUserMap.values()
                    .stream()
                    //同步时间小于5分钟的算在线，否则为离线
                    .filter(videoRoomUserDto -> videoRoomUserDto.getSyncTime().plusMinutes(5).isAfter(LocalDateTime.now()))
                    .toList();
        }
    }

    /**
     * 将当前用户从观众列表移除
     */
    private void removeFromRoomUser(String roomId) {
        String roomUserKey = VIDEO_ROOM_USER_KEY + roomId;
        Map<Long, VideoRoomUserDto> roomUserMap = CacheUtils.getCacheObject(roomUserKey);
        if (roomUserMap != null) {
            LoginUser loginUser = LoginMessageUtil.getLoginUser().get();
            roomUserMap.remove(loginUser.getUserId());
        }
    }

    private VideoRoom assertRoomExists(String roomId) {
        return videoRoomDao.selectByRoomId(roomId)
                .orElseThrow(() -> new ServiceException(HttpStatus.NOT_FOUND, "放映室不存在或已关闭"));
    }

    private VideoRoomEventDto saveRoomEvent(String roomId, String eventType, LoginUser loginUser, String state,
                                            Long videoTimeSeconds, Long clientSendTime, String content, String payload) {
        VideoRoomEvent event = new VideoRoomEvent();
        event.setRoomId(roomId);
        event.setEventType(eventType);
        event.setUserId(loginUser.getUserId());
        event.setUserName(loginUser.getUserName());
        event.setState(state);
        event.setVideoTimeSeconds(videoTimeSeconds);
        event.setClientSendTime(clientSendTime);
        event.setServerReceiveTime(System.currentTimeMillis());
        event.setContent(content);
        event.setPayload(payload);
        event.setCreateTime(LocalDateTime.now());
        return convertToEventDto(videoRoomEventRepository.save(event));
    }

    private VideoRoomCommentDto convertToCommentDto(VideoRoomEvent event) {
        VideoRoomCommentDto dto = new VideoRoomCommentDto();
        dto.setId(event.getId());
        dto.setUserId(event.getUserId());
        dto.setUserName(event.getUserName());
        dto.setContent(event.getContent());
        dto.setCreateTime(event.getCreateTime());
        return dto;
    }

    private VideoRoomEventDto convertToEventDto(VideoRoomEvent event) {
        VideoRoomEventDto dto = new VideoRoomEventDto();
        dto.setId(event.getId());
        dto.setRoomId(event.getRoomId());
        dto.setEventType(event.getEventType());
        dto.setUserId(event.getUserId());
        dto.setUserName(event.getUserName());
        dto.setState(event.getState());
        dto.setVideoTimeSeconds(event.getVideoTimeSeconds());
        dto.setClientSendTime(event.getClientSendTime());
        dto.setServerReceiveTime(event.getServerReceiveTime());
        dto.setContent(event.getContent());
        dto.setPayload(event.getPayload());
        dto.setCreateTime(event.getCreateTime());
        return dto;
    }

    private void validatePlayMyVideoRequest(PlayMyVideoRequestDto requestDto, LoginUser loginUser) {
        boolean fileExists = fileService.existsUserFile(requestDto.getDirectoryOpenFlag(),
                loginUser.getUserId().toString(), requestDto.getFilePath(), false);
        if (!fileExists) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "视频文件不存在或已被删除");
        }
    }

    private String extractDirectoryPath(String filePath) {
        String normalizedPath = FilePathGuard.normalizeRelativePath(filePath, false);
        int index = normalizedPath.lastIndexOf('/');
        return index < 0 ? "" : normalizedPath.substring(0, index);
    }

    private String buildInternalVideoPath(Integer openType, String filePath, boolean preferTranscoded) {
        String normalizedPath = FilePathGuard.normalizeRelativePath(filePath, false);
        String actionPath = preferTranscoded ? "transcodedVideo" : "stream";
        return "fileServer/file/" + actionPath
                + "?openType=" + openType
                + "&filePath=" + URLEncoder.encode(normalizedPath, StandardCharsets.UTF_8);
    }
}
