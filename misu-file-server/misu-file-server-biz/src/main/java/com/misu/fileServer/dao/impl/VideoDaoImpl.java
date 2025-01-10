package com.misu.fileServer.dao.impl;

import com.misu.fileServer.dao.VideoRoomDao;
import com.misu.fileServer.domain.entity.QVideoRoom;
import com.misu.fileServer.domain.entity.VideoRoom;
import com.misu.fileServer.repository.VideoRoomRepository;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.querydsl.jpa.impl.JPAUpdateClause;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 用户数据层
 */
@Component
public class VideoDaoImpl implements VideoRoomDao {

    private final QVideoRoom videoRoomModel = QVideoRoom.videoRoom;

    @Resource
    private JPAQueryFactory fileServerJpaQueryFactory;

    @Resource
    private VideoRoomRepository videoRoomRepository;

    @Override
    public Optional<VideoRoom> selectByRoomId(String roomId) {
        return Optional.ofNullable(
                fileServerJpaQueryFactory.selectFrom(videoRoomModel)
                        .from(videoRoomModel)
                        .where(videoRoomModel.roomId.eq(roomId)
                                .and(videoRoomModel.expireTime.after(LocalDateTime.now()))
                        )
                        .limit(1)
                        .fetchFirst());
    }

    @Override
    public Optional<VideoRoom> selectOneByCreatorId(String creatorId) {
        return Optional.ofNullable(
            fileServerJpaQueryFactory.selectFrom(videoRoomModel)
                    .from(videoRoomModel)
                    .where(videoRoomModel.creatorId.eq(creatorId)
                            .and(videoRoomModel.expireTime.after(LocalDateTime.now()))
                    )
                    .limit(1)
                    .fetchFirst());
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public VideoRoom save(VideoRoom entity) {
        return videoRoomRepository.save(entity);
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public long updateNotNullById(VideoRoom entity) {
        JPAUpdateClause updateClause = fileServerJpaQueryFactory
                .update(videoRoomModel);
        if (entity.getRoomName() != null) {
            updateClause.set(videoRoomModel.roomName, entity.getRoomName());
        }
        if (entity.getVideoPath() != null) {
            updateClause.set(videoRoomModel.videoPath, entity.getVideoPath());
        }
        if (entity.getRemark() != null) {
            updateClause.set(videoRoomModel.remark, entity.getRemark());
        }
        if (entity.getState() != null) {
            updateClause.set(videoRoomModel.state, entity.getState());
        }
        if (entity.getVideoTime() != null) {
            updateClause.set(videoRoomModel.videoTime, entity.getVideoTime());
        }
        if (entity.getSyncTime() != null) {
            updateClause.set(videoRoomModel.syncTime, entity.getSyncTime());
        }

        return updateClause
                .where(videoRoomModel.id.eq(entity.getId()))
                .execute();
    }
}
