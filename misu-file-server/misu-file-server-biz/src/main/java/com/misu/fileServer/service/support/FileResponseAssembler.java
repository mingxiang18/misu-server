package com.misu.fileServer.service.support;

import com.misu.fileServer.constant.FileType;
import com.misu.fileServer.constant.VideoTranscodeState;
import com.misu.fileServer.domain.dto.FileRequestDto;
import com.misu.fileServer.domain.dto.FileResponseDto;
import com.misu.fileServer.domain.dto.TrashFileResponseDto;
import com.misu.fileServer.domain.dto.VideoTranscodeStatusDto;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.service.PreviewService;
import com.misu.fileServer.service.VideoTranscodeService;
import com.misu.fileServer.util.FilePathGuard;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * FileMapping → 响应 DTO 的组装组件。
 *
 * <p>从 {@code FileServiceImpl} 抽出的「DTO 组装」职责：把 {@link FileMapping} 实体映射成
 * {@link FileResponseDto} / {@link TrashFileResponseDto}，并补齐预览链接、视频转码信息、
 * 以及（不带 token 的）用户文件访问链接（下载 / 预览 / 流播 / 视频封面 / 转码视频）。
 * 方法签名 / 逻辑与原 god class 完全一致，仅可见性放宽为 public 供 FileServiceImpl 委托。</p>
 *
 * <p>链接生成：{@link #createUserFileAccessLink} 是不依赖登录态 / token 的纯路径拼装（仅
 * {@link FilePathGuard#normalizeRelativePath} + URL 编码），与 FileServiceImpl 的临时下载
 * token 链接（{@code createFileDownloadLink}）无耦合，故随 assembler 一并搬入并自洽。
 * FileServiceImpl 其它处需要拼此链接时改委托本组件，消除重复实现。</p>
 *
 * @author misu
 */
@Component
public class FileResponseAssembler {

    @Resource
    private PreviewService previewService;

    @Resource
    private VideoTranscodeService videoTranscodeService;

    @Resource
    private FileMappingRepository fileMappingRepository;

    @Resource
    private FilePathResolver filePathResolver;

    /**
     * 从指定目录获取文件列表（按 parentPath 查 repository 再映射成 DTO 列表）。
     */
    public List<FileResponseDto> getFileListFromDirectory(FileRequestDto fileRequestDto, String userId) {
        String requestPath = FilePathGuard.normalizeRelativePath(fileRequestDto.getFilePath(), true);
        return fileMappingRepository.findByOpenTypeAndUserIdAndParentPathAndDeletedFalseOrderByFileTypeDescFileNameAsc(
                        fileRequestDto.getOpenType(), userId, requestPath)
                .stream()
                .map(this::toFileResponseDto)
                .filter(dto -> dto.getFile() != null && dto.getFile().exists())
                .collect(Collectors.toList());
    }

    public FileResponseDto toFileResponseDto(FileMapping mapping) {
        FileResponseDto fileResponseDto = new FileResponseDto();
        fileResponseDto.setFileName(mapping.getFileName());
        fileResponseDto.setFileSize(mapping.getFileSize());
        fileResponseDto.setFileType(mapping.getFileType());
        fileResponseDto.setFile(filePathResolver.resolveMappedFile(mapping));
        fileResponseDto.setFilePath("/" + (StringUtils.isBlank(mapping.getParentPath()) ? "" : mapping.getParentPath() + "/"));
        return fileResponseDto;
    }

    public void packagePreviewLink(Integer openType, FileResponseDto responseDto) {
        //图片类型的文件预览链接设置
        if (FileType.IMAGE_FILE.equals(responseDto.getFileType())) {
            File previewFile = filePathResolver.getPreviewFile(responseDto.getFile());
            //如果预览文件存在，生成预览链接，如果不存在，添加到缩略图生成队列
            if (previewFile.exists()) {
                responseDto.setPreviewLink(createUserFileAccessLink("/preview",
                        responseDto.getFilePath() + responseDto.getFileName(), openType));
            }else {
                previewService.generatePreviewFile(responseDto.getFile());
            }
        }
    }

    public void packageVideoTranscodeInfo(Integer openType, String mappingUserId, FileResponseDto responseDto) {
        if (!FileType.VIDEO_FILE.equals(responseDto.getFileType())) {
            return;
        }

        String virtualPath = StringUtils.defaultString(responseDto.getFilePath())
                + StringUtils.defaultString(responseDto.getFileName());
        if (virtualPath.startsWith("/")) {
            virtualPath = virtualPath.substring(1);
        }
        VideoTranscodeStatusDto status = videoTranscodeService.getOrCreateTranscodeStatus(
                responseDto.getFile(), openType, mappingUserId, virtualPath);
        responseDto.setTranscodeState(status.getState());
        responseDto.setTranscodeProgress(status.getProgress());
        responseDto.setTranscodeMessage(status.getMessage());
        responseDto.setTranscodeMaxBytes(videoTranscodeService.getMaxBytes());

        if (videoTranscodeService.getVideoPreviewFile(responseDto.getFile()).exists()) {
            responseDto.setVideoPreviewLink(createUserFileAccessLink("/videoPreview",
                    responseDto.getFilePath() + responseDto.getFileName(), openType));
        }
        if (VideoTranscodeState.SUCCESS.equals(status.getState())
                || VideoTranscodeState.PASSTHROUGH.equals(status.getState())) {
            // SUCCESS：拉转码产物 /transcodedVideo；
            // PASSTHROUGH：源文件已满足 Safari 播放，/transcodedVideo 内部检测到 PASSTHROUGH 后会直接服务源文件，
            // 所以这里两种状态都用同一个 link，前端无差别。
            String transcodedStreamLink = createUserFileAccessLink("/transcodedVideo",
                    responseDto.getFilePath() + responseDto.getFileName(), openType);
            responseDto.setTranscodedStreamLink(transcodedStreamLink);
        }
    }

    public TrashFileResponseDto toTrashResponseDto(FileMapping mapping) {
        TrashFileResponseDto dto = new TrashFileResponseDto();
        dto.setId(mapping.getId());
        dto.setFileName(mapping.getFileName());
        dto.setFileType(mapping.getFileType());
        dto.setFileSize(mapping.getFileSize());
        dto.setOriginalPath(mapping.getVirtualPath());
        dto.setOriginalParentPath(mapping.getParentPath());
        dto.setDeleteTime(mapping.getUpdateTime() != null ? mapping.getUpdateTime() : mapping.getCreateTime());
        return dto;
    }

    /**
     * 拼装（不带 token 的）用户文件访问链接。逻辑与原 FileServiceImpl 完全一致。
     */
    public String createUserFileAccessLink(String accessPath, String filePath, Integer openType) {
        String relativePath = FilePathGuard.normalizeRelativePath(filePath);
        return "fileServer/file" + accessPath + "?openType=" + openType
                + "&filePath=" + URLEncoder.encode(relativePath, StandardCharsets.UTF_8);
    }
}
