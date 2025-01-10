package com.misu.fileServer.service.impl;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.common.util.CacheUtils;
import com.misu.fileServer.dao.VideoRoomDao;
import com.misu.fileServer.domain.dto.*;
import com.misu.fileServer.domain.entity.VideoRoom;
import com.misu.fileServer.service.VideoRoomService;
import com.misu.security.dto.LoginUser;
import com.misu.security.utils.LoginMessageUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final static String VIDEO_STATE_KEY = "video-room-state:";

    private final static String VIDEO_ROOM_USER_KEY = "video-room-user:";

    @Override
    public VideoRoomDto getVideoRoomFromId(String roomId) {
        Optional<VideoRoom> videoRoomOptional = videoRoomDao.selectByRoomId(roomId);

        if (videoRoomOptional.isPresent()) {
            VideoRoom videoRoom = videoRoomOptional.get();
            VideoRoomDto videoRoomDto = new VideoRoomDto();
            videoRoomDto.setRoomId(videoRoom.getRoomId());
            videoRoomDto.setRoomName(videoRoom.getRoomName());
            videoRoomDto.setVideoPath(videoRoom.getVideoPath());
            videoRoomDto.setCreatorId(videoRoom.getCreatorId());
            videoRoomDto.setCreateTime(videoRoom.getCreateTime());
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
    @Transactional(transactionManager = "fileServerTransactionManager")
    public VideoRoomDto createVideoRoom(CreateVideoRoomRequestDto createVideoRoomRequestDto) {
        Optional<LoginUser> loginUserOptional = LoginMessageUtil.getLoginUser();
        if (loginUserOptional.isEmpty()) {
            return null;
        }

        LoginUser loginUser = loginUserOptional.get();

        VideoRoom videoRoom = new VideoRoom();
        videoRoom.setRoomId(UUID.randomUUID().toString());
        videoRoom.setRoomName(createVideoRoomRequestDto.getRoomName());
        videoRoom.setVideoPath(createVideoRoomRequestDto.getVideoPath());
        videoRoom.setCreatorId(String.valueOf(loginUser.getUserId()));
        videoRoom.setCreateTime(LocalDateTime.now());
        videoRoom.setState("pause");
        videoRoom.setVideoTime(LocalTime.of(0, 0, 0));
        videoRoom.setSyncTime(LocalDateTime.now());
        //1天后过期
        videoRoom.setExpireTime(LocalDateTime.now().plusDays(1));
        videoRoomDao.save(videoRoom);

        //返回id
        VideoRoomDto videoRoomDto = new VideoRoomDto();
        videoRoomDto.setRoomId(videoRoom.getRoomId());
        return videoRoomDto;
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
        videoRoomDao.updateNotNullById(videoRoom);

        //更新缓存
        String cacheKey = VIDEO_STATE_KEY + updateVideoStateRequestDto.getRoomId();
        VideoStateInRoomDto videoStateInRoomDto = new VideoStateInRoomDto();
        videoStateInRoomDto.setRoomId(videoRoom.getRoomId());
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
        videoRoomDao.updateNotNullById(videoRoom);

        //删除缓存
        String roomUserKey = VIDEO_ROOM_USER_KEY + videoRoomRequestDto.getRoomId();
        String videoStateKey = VIDEO_STATE_KEY + videoRoomRequestDto.getRoomId();
        CacheUtils.removeCacheObject(roomUserKey);
        CacheUtils.removeCacheObject(videoStateKey);
    }

    /**
     * 添加当前登陆账号到房间用户列表
     */
    private void addToRoomUser(String roomId) {
        String roomUserKey = VIDEO_ROOM_USER_KEY + roomId;
        Map<Long, VideoRoomUserDto> roomUserMap = CacheUtils.getCacheObject(roomUserKey);
        if (roomUserMap == null) {
            roomUserMap = new HashMap<>();
            //10分钟后过期
            CacheUtils.setCacheObject(roomUserKey, roomUserMap, 10, ChronoUnit.MINUTES);
        }
        LoginUser loginUser = LoginMessageUtil.getLoginUser().get();

        //如果当前用户不存在，添加到房间用户列表
        if (!roomUserMap.containsKey(loginUser.getUserId())) {
            roomUserMap.put(loginUser.getUserId(), new VideoRoomUserDto(loginUser.getUserName(), LocalDateTime.now()));
        }
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
}
