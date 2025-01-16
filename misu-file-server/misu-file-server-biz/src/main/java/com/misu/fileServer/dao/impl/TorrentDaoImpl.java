package com.misu.fileServer.dao.impl;

import com.misu.common.util.QuerydslDaoGenerationUtil;
import com.misu.fileServer.dao.TorrentDao;
import com.misu.fileServer.domain.dto.*;
import com.misu.fileServer.domain.entity.*;
import com.misu.fileServer.repository.RssInfoRepository;
import com.misu.fileServer.repository.TorrentInfoRepository;
import com.misu.fileServer.repository.TorrentUserRelationRepository;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.QueryResults;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import com.querydsl.jpa.impl.JPAUpdateClause;
import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotNull;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 磁力信息数据层
 */
@Component
public class TorrentDaoImpl implements TorrentDao {

    private final QTorrentInfo torrentInfoModel = QTorrentInfo.torrentInfo;

    private final QTorrentUserRelation torrentUserRelationModel = QTorrentUserRelation.torrentUserRelation;

    private final QRssInfo rssInfoModel = QRssInfo.rssInfo;

    @Resource
    private JPAQueryFactory fileServerJpaQueryFactory;

    @Resource
    private TorrentInfoRepository torrentInfoRepository;

    @Resource
    private TorrentUserRelationRepository torrentUserRelationRepository;

    @Resource
    private RssInfoRepository rssInfoRepository;


    @Override
    public Optional<TorrentInfo> selectTorrentInfoByHash(String torrentHash) {
        return Optional.ofNullable(
                fileServerJpaQueryFactory.selectFrom(torrentInfoModel)
                        .where(
                                torrentInfoModel.torrentHash.eq(torrentHash)
                        )
                        .fetchFirst());
    }

    @Override
    public List<TorrentInfoDto> selectTorrentInfoList(TorrentInfoQueryRequestDto torrentInfoQueryRequestDto) {
        //构造where参数
        BooleanBuilder booleanBuilder = new BooleanBuilder();
        if (StringUtils.isNotEmpty(torrentInfoQueryRequestDto.getTorrentHash())) {
            booleanBuilder.and(torrentInfoModel.torrentHash.eq(torrentInfoQueryRequestDto.getTorrentHash()));
        }
        if (StringUtils.isNotEmpty(torrentInfoQueryRequestDto.getTorrentName())) {
            booleanBuilder.and(torrentInfoModel.torrentName.eq(torrentInfoQueryRequestDto.getTorrentName()));
        }
        if (torrentInfoQueryRequestDto.getServerFileState() != null) {
            booleanBuilder.and(torrentInfoModel.state.eq(torrentInfoQueryRequestDto.getServerFileState()));
        }
        if (torrentInfoQueryRequestDto.getCompleteFlag() != null) {
            //判断是否完成，只需要判断状态是否大于等于30
            if (torrentInfoQueryRequestDto.getCompleteFlag()) {
                booleanBuilder.and(torrentInfoModel.state.goe(30));
            }else {
                booleanBuilder.and(torrentInfoModel.state.lt(30));
            }
        }

        return fileServerJpaQueryFactory.select(Projections.bean(
                    TorrentInfoDto.class,
                    torrentInfoModel.id,
                    torrentInfoModel.torrentHash,
                    torrentInfoModel.torrentName,
                    torrentInfoModel.torrentUrl,
                    torrentInfoModel.downloadPath,
                    torrentInfoModel.totalSize,
                    torrentInfoModel.state,
                    torrentInfoModel.remark
                ))
                .from(torrentInfoModel)
                .where(
                        booleanBuilder
                ).fetch();
    }

    @Override
    public List<UserTorrentDetailDto> selectUserTorrent(UserTorrentQueryRequestDto userTorrentQueryRequestDto) {
        JPAQuery<UserTorrentDetailDto> query = getUserTorrentDetailDtoJPAQuery(userTorrentQueryRequestDto);
        return query.fetch();
    }

    @Override
    public Page<UserTorrentDetailDto> selectUserTorrentByPage(UserTorrentQueryRequestDto userTorrentQueryRequestDto, PageRequest pageRequest) {
        JPAQuery<UserTorrentDetailDto> query = getUserTorrentDetailDtoJPAQuery(userTorrentQueryRequestDto);
        //设置分页参数
        query.offset((long) pageRequest.getPageNumber() * pageRequest.getPageSize())
                .limit(pageRequest.getPageSize());
        //执行查询
        QueryResults<UserTorrentDetailDto> queryResults = query.fetchResults();
        //返回分页列表
        return new PageImpl<>(queryResults.getResults(), pageRequest, queryResults.getTotal());
    }

    private JPAQuery<UserTorrentDetailDto> getUserTorrentDetailDtoJPAQuery(UserTorrentQueryRequestDto userTorrentQueryRequestDto) {
        //构造where参数
        BooleanBuilder booleanBuilder = new BooleanBuilder();
        if (StringUtils.isNotEmpty(userTorrentQueryRequestDto.getUserId())) {
            booleanBuilder.and(torrentUserRelationModel.userId.eq(userTorrentQueryRequestDto.getUserId()));
        }
        if (userTorrentQueryRequestDto.getUserTorrentId() != null) {
            booleanBuilder.and(torrentUserRelationModel.id.eq(userTorrentQueryRequestDto.getUserTorrentId()));
        }
        if (userTorrentQueryRequestDto.getUserFileState() != null) {
            booleanBuilder.and(torrentUserRelationModel.state.eq(userTorrentQueryRequestDto.getUserFileState()));
        }
        if (StringUtils.isNotEmpty(userTorrentQueryRequestDto.getUserFilePath())) {
            booleanBuilder.and(torrentUserRelationModel.userFilePath.eq(userTorrentQueryRequestDto.getUserFilePath()));
        }
        if (userTorrentQueryRequestDto.getServerFileState() != null) {
            booleanBuilder.and(torrentInfoModel.state.eq(userTorrentQueryRequestDto.getServerFileState()));
        }
        if (StringUtils.isNotEmpty(userTorrentQueryRequestDto.getTorrentHash())) {
            booleanBuilder.and(torrentInfoModel.torrentHash.eq(userTorrentQueryRequestDto.getTorrentHash()));
        }
        if (StringUtils.isNotEmpty(userTorrentQueryRequestDto.getKeyword())) {
            booleanBuilder.and(
                    torrentInfoModel.torrentName.like("%" + userTorrentQueryRequestDto.getKeyword() + "%")
                    .or(torrentUserRelationModel.userFilePath.like("%" + userTorrentQueryRequestDto.getKeyword() + "%")
                    .or(torrentUserRelationModel.torrentHash.eq("%" + userTorrentQueryRequestDto.getKeyword() + "%"))));
        }
        if (userTorrentQueryRequestDto.getCompleteState() != null) {
            if (userTorrentQueryRequestDto.getCompleteState() == 0) {
                //未完成，需要状态是0-文件未同步且服务器下载状态小于30-已完成
                booleanBuilder.and(torrentUserRelationModel.state.eq(0));
                booleanBuilder.and(torrentInfoModel.state.lt(30));
            }else if (userTorrentQueryRequestDto.getCompleteState() == 30) {
                //已完成，需要状态是1-文件已同步
                booleanBuilder.and(torrentUserRelationModel.state.eq(1));
            }else if (userTorrentQueryRequestDto.getCompleteState() == 99) {
                //失败，需要状态是2-文件同步失败或者服务器状态为99-失败
                booleanBuilder.and(torrentUserRelationModel.state.eq(2).or(torrentInfoModel.state.eq(99)));
            }
        }

        JPAQuery<UserTorrentDetailDto> query = fileServerJpaQueryFactory.select(Projections.bean(
                        UserTorrentDetailDto.class,
                        torrentUserRelationModel.id.as("userTorrentId"),
                        torrentUserRelationModel.userId,
                        torrentUserRelationModel.userFilePath,
                        torrentUserRelationModel.state.as("userFileState"),
                        torrentUserRelationModel.torrentHash,
                        torrentUserRelationModel.failedReason,
                        torrentInfoModel.torrentUrl,
                        torrentInfoModel.torrentName,
                        torrentInfoModel.totalSize,
                        torrentInfoModel.state.as("serverFileState"),
                        torrentInfoModel.downloadPath.as("serverDownloadPath"),
                        torrentInfoModel.remark
                ))
                .from(torrentUserRelationModel)
                .innerJoin(torrentInfoModel).on(torrentInfoModel.torrentHash.eq(torrentUserRelationModel.torrentHash))
                .where(
                        booleanBuilder
                )
                .orderBy(torrentUserRelationModel.createTime.desc());
        return query;
    }

    @Override
    public List<RssInfoDto> selectRssList(RssQueryRequestDto rssQueryRequestDto) {
        JPAQuery<RssInfoDto> query = getRssInfoDtoJPAQuery(rssQueryRequestDto);
        return query.fetch();
    }

    @Override
    public Page<RssInfoDto> selectRssListByPage(RssQueryRequestDto rssQueryRequestDto, PageRequest pageRequest) {
        JPAQuery<RssInfoDto> query = getRssInfoDtoJPAQuery(rssQueryRequestDto);
        //设置分页参数
        query.offset((long) pageRequest.getPageNumber() * pageRequest.getPageSize())
                .limit(pageRequest.getPageSize());
        //执行查询
        QueryResults<RssInfoDto> queryResults = query.fetchResults();
        //返回分页列表
        return new PageImpl<>(queryResults.getResults(), pageRequest, queryResults.getTotal());
    }

    private JPAQuery<RssInfoDto> getRssInfoDtoJPAQuery(RssQueryRequestDto rssQueryRequestDto) {
        //构造where参数
        BooleanBuilder booleanBuilder = new BooleanBuilder();
        if (rssQueryRequestDto.getRssId() != null) {
            booleanBuilder.and(rssInfoModel.id.eq(rssQueryRequestDto.getRssId()));
        }
        if (StringUtils.isNotEmpty(rssQueryRequestDto.getRssUrl())) {
            booleanBuilder.and(rssInfoModel.rssUrl.eq(rssQueryRequestDto.getRssUrl()));
        }
        if (StringUtils.isNotEmpty(rssQueryRequestDto.getRssName())) {
            booleanBuilder.and(rssInfoModel.rssName.like(rssQueryRequestDto.getRssUrl()));
        }
        if (rssQueryRequestDto.getState() != null) {
            booleanBuilder.and(rssInfoModel.state.eq(rssQueryRequestDto.getState()));
        }
        if (StringUtils.isNotEmpty(rssQueryRequestDto.getCreatorId())) {
            booleanBuilder.and(rssInfoModel.creatorId.eq(rssQueryRequestDto.getCreatorId()));
        }
        if (StringUtils.isNotEmpty(rssQueryRequestDto.getKeyword())) {
            booleanBuilder.and(
                    rssInfoModel.rssName.like("%" + rssQueryRequestDto.getKeyword() + "%")
                    .or(rssInfoModel.downloadPath.like("%" + rssQueryRequestDto.getKeyword() + "%"))
            );
        }

        JPAQuery<RssInfoDto> query = fileServerJpaQueryFactory.select(Projections.bean(
                        RssInfoDto.class,
                        rssInfoModel.id,
                        rssInfoModel.rssUrl,
                        rssInfoModel.rssName,
                        rssInfoModel.downloadPath,
                        rssInfoModel.remark,
                        rssInfoModel.creatorId,
                        rssInfoModel.state,
                        rssInfoModel.createTime
                ))
                .from(rssInfoModel)
                .where(
                        booleanBuilder
                )
                .orderBy(rssInfoModel.createTime.desc());
        return query;
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void saveTorrentInfo(TorrentInfo torrentInfo) {
        torrentInfoRepository.save(torrentInfo);
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public long updateTorrentInfo(TorrentInfo entity) {
        JPAUpdateClause updateClause = fileServerJpaQueryFactory
                .update(torrentInfoModel);
        if (entity.getTorrentName() != null) {
            updateClause.set(torrentInfoModel.torrentName, entity.getTorrentName());
        }
        if (entity.getDownloadPath() != null) {
            updateClause.set(torrentInfoModel.downloadPath, entity.getDownloadPath());
        }
        if (entity.getTotalSize() != null) {
            updateClause.set(torrentInfoModel.totalSize, entity.getTotalSize());
        }
        if (entity.getState() != null) {
            updateClause.set(torrentInfoModel.state, entity.getState());
        }
        if (entity.getRemark() != null) {
            updateClause.set(torrentInfoModel.remark, entity.getRemark());
        }
        return updateClause
                .where(torrentInfoModel.torrentHash.eq(entity.getTorrentHash()))
                .execute();
    }

    @Override
    public long updateTorrentState(@NotNull String torrentHash, @NotNull int state, @NotNull String remark) {
        JPAUpdateClause updateClause = fileServerJpaQueryFactory
                .update(torrentInfoModel);
        updateClause.set(torrentInfoModel.state, state);
        updateClause.set(torrentInfoModel.remark, remark);
        return updateClause
                .where(torrentInfoModel.torrentHash.eq(torrentHash)
                        //必须未完成的才允许修改
                        .and(torrentInfoModel.state.ne(30)))
                .execute();
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void saveTorrentUserRelation(TorrentUserRelation torrentUserRelation) {
        torrentUserRelationRepository.save(torrentUserRelation);
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public long updateTorrentUserRelation(TorrentUserRelation entity) {
        JPAUpdateClause updateClause = fileServerJpaQueryFactory
                .update(torrentUserRelationModel);
        if (entity.getUserFilePath() != null) {
            updateClause.set(torrentUserRelationModel.userFilePath, entity.getUserFilePath());
        }
        if (entity.getState() != null) {
            updateClause.set(torrentUserRelationModel.state, entity.getState());
        }
        if (entity.getFailedReason() != null) {
            updateClause.set(torrentUserRelationModel.failedReason, entity.getFailedReason());
        }
        return updateClause
                .where(
                        torrentUserRelationModel.id.eq(entity.getId())
                        //必须未同步的才允许修改
                        .and(torrentUserRelationModel.state.ne(1)))
                .execute();
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void deleteTorrentUserRelation(Long userTorrentId) {
        torrentUserRelationRepository.deleteById(userTorrentId);
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void saveRssInfo(RssInfo rssInfo) {
        rssInfoRepository.save(rssInfo);
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public long updateRssInfo(RssInfo entity) {
        JPAUpdateClause updateClause = fileServerJpaQueryFactory
                .update(rssInfoModel);
        if (entity.getRssUrl() != null) {
            updateClause.set(rssInfoModel.rssUrl, entity.getRssUrl());
        }
        if (entity.getRssName() != null) {
            updateClause.set(rssInfoModel.rssName, entity.getRssName());
        }
        if (entity.getDownloadPath() != null) {
            updateClause.set(rssInfoModel.downloadPath, entity.getDownloadPath());
        }
        if (entity.getRemark() != null) {
            updateClause.set(rssInfoModel.remark, entity.getRemark());
        }
        if (entity.getState() != null) {
            updateClause.set(rssInfoModel.state, entity.getState());
        }
        return updateClause
                .where(rssInfoModel.id.eq(entity.getId()))
                .execute();
    }

    @Override
    @Transactional(transactionManager = "fileServerTransactionManager")
    public void deleteRssInfo(Long id) {
        rssInfoRepository.deleteById(id);
    }

    public static void main(String[] args) {
        QuerydslDaoGenerationUtil.generateUpdate(RssInfo.class, "rssInfoModel");
    }
}
