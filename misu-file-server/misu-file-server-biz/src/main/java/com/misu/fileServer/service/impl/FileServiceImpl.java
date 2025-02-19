package com.misu.fileServer.service.impl;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.common.util.ZipUtils;
import com.misu.fileServer.constant.FileType;
import com.misu.fileServer.domain.dto.*;
import com.misu.fileServer.service.FileService;
import com.misu.fileServer.service.PreviewService;
import com.misu.fileServer.util.FileTypeUtils;
import com.misu.framework.config.file.FilePathConfig;
import com.misu.framework.fileClient.FileClientApi;
import com.misu.security.constant.UserRole;
import com.misu.security.utils.AuthorityUtil;
import com.misu.security.dto.LoginUser;
import com.misu.security.service.TokenService;
import com.misu.security.utils.LoginMessageUtil;
import io.jsonwebtoken.Claims;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpRange;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 文件相关Service
 *
 * @author misu
 */
@Slf4j
@Service
public class FileServiceImpl implements FileService {

    private final static String PUBLIC_DIRECTORY = "public/";
    private final static String PRIVATE_DIRECTORY = "private/";

    @Value("${file-server.path}")
    private String fileServerPath;

    @Resource
    private TokenService tokenService;
    
    @Resource
    private PreviewService previewService;

    /**
     * 初始化检查文件目录是否存在，不存在则创建
     */
    @PostConstruct
    public void initFileDirectory() {
        File publicDirectory = new File(fileServerPath + PUBLIC_DIRECTORY);
        if (!publicDirectory.exists()) {
            publicDirectory.mkdirs();
        }

        File privateDirectory = new File(fileServerPath + PRIVATE_DIRECTORY);
        if (!privateDirectory.exists()) {
            privateDirectory.mkdirs();
        }
    }

    @Override
    public List<FileResponseDto> getFileList(FileRequestDto fileRequestDto) {
        Optional<LoginUser> loginUser = LoginMessageUtil.getLoginUser();
        if (loginUser.isPresent()) {
            String directory = fileRequestDto.getOpenType() == 1 ?
                    fileServerPath + PUBLIC_DIRECTORY :
                    fileServerPath + PRIVATE_DIRECTORY + loginUser.get().getUserId() + "/";
            List<FileResponseDto> fileList = getFileListFromDirectory(fileRequestDto.getFilePath(), directory);
            for (FileResponseDto responseDto : fileList) {
                //封装文件预览路径
                packagePreviewLink(directory, responseDto);

                //设置下载路径
                responseDto.setDownloadLink(getFileDownloadLink(new FileRequestDto(fileRequestDto.getFilePath() + responseDto.getFileName(),
                        fileRequestDto.getOpenType())));

                responseDto.setFile(null);
            }
            return fileList;
        }else {
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "用户未登录或未认证使用文件系统");
        }
    }

    private void packagePreviewLink(String directory, FileResponseDto responseDto) {
        //图片类型的文件预览链接设置
        if (FileType.IMAGE_FILE.equals(responseDto.getFileType())) {
            String previewPath = (directory + responseDto.getFilePath().substring(1) + responseDto.getFileName())
                    .replace(fileServerPath, fileServerPath + "preview/");
            File previewFile = new File(previewPath);
            //如果预览文件存在，生成预览链接，如果不存在，添加到缩略图生成队列
            if (previewFile.exists()) {
                responseDto.setPreviewLink(createFileDownloadLink(previewPath.replace(fileServerPath, "")));
            }else {
                previewService.generatePreviewFile(responseDto.getFile());
            }
        }
    }

    /**
     * 从指定目录获取文件列表
     */
    private static List<FileResponseDto> getFileListFromDirectory(String filePath, String directory) {
        File directoryFile = new File(directory);
        File file = new File(directory + filePath);
        if (!file.exists()) {
            return new ArrayList<>();
        }

        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files == null) {
                return new ArrayList<>();
            }else {
                //封装文件展示列表
                return Arrays.stream(files).map(oneFile -> {
                    FileResponseDto fileResponseDto = new FileResponseDto();
                    fileResponseDto.setFileName(oneFile.getName());
                    fileResponseDto.setFileSize(oneFile.length());
                    fileResponseDto.setFileType(FileTypeUtils.getFileType(oneFile));
                    fileResponseDto.setFile(oneFile);

                    String fileRelativePath = getRelativePath(oneFile, directoryFile);
                    fileResponseDto.setFilePath("/" + (StringUtils.isBlank(fileRelativePath) ? "" : fileRelativePath + "/"));

                    return fileResponseDto;
                }).collect(Collectors.toList());
            }
        }
        return new ArrayList<>();
    }

    /**
     * 获取相对路径
     */
    private static String getRelativePath(File originFile, File directoryFile) {
        // 使用 Path 把绝对路径中私密路径去掉，返回相对路径
        Path path = Paths.get(originFile.getParentFile().getAbsolutePath());
        Path base = Paths.get(directoryFile.getAbsolutePath());
        return base.relativize(path).toString().replace("\\", "/");
    }

    @Override
    public String getFileDownloadLink(FileRequestDto fileRequestDto) {
        String filePath = fileRequestDto.getOpenType() == 1 ?
                PUBLIC_DIRECTORY + fileRequestDto.getFilePath() :
                PRIVATE_DIRECTORY + LoginMessageUtil.getLoginUser().get().getUserId() + "/" + fileRequestDto.getFilePath();

        return createFileDownloadLink(filePath);
    }

    /**
     * 创建文件临时下载链接
     */
    private String createFileDownloadLink(String filePath) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", LoginMessageUtil.getLoginUser().get().getUserId());
        claims.put("filePath", filePath);
        return "fileServer/file/downloadFile?fileToken=" + tokenService.createToken(claims);
    }

    @Override
    public void downloadFile(FileDownloadRequestDto fileRequestDto, HttpServletRequest request, HttpServletResponse response) {
        try {
            Claims claims = tokenService.parseToken(fileRequestDto.getFileToken());
            String tokenFilePath = claims.get("filePath", String.class);
            if (claims.getExpiration().after(new Date()) ) {
                File file = new File(fileServerPath + tokenFilePath);
                if (file.exists()) {
                    writeFileToResponse(response, request, file);
                    return;
                }else {
                    throw new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在或已被删除");
                }
            }
        }catch (ServiceException se) {
            throw se;
        }catch (Exception e) {
            log.error("token解析失败", e);
            throw new ServiceException(HttpStatus.FORBIDDEN, "下载链接已过期");
        }
    }

    @Override
    public FileUploadResponse uploadFile(FileUploadRequest fileUploadRequest) {
        if (fileUploadRequest.getOpenType() == 1
                && !AuthorityUtil.hasAuthority(Arrays.asList(UserRole.ADMIN, UserRole.FILE_ADMIN))) {
            throw new ServiceException(HttpStatus.UNAUTHORIZED, "当前用户无法上传公共文件");
        }

        String directory = fileUploadRequest.getOpenType() == 1 ?
                fileServerPath + PUBLIC_DIRECTORY :
                fileServerPath + PRIVATE_DIRECTORY + LoginMessageUtil.getLoginUser().get().getUserId() + "/";

        File file = new File(directory + fileUploadRequest.getFilePath() + "/" + fileUploadRequest.getFileName());

        //如果不允许覆盖且文件已存在，返回提示
        if (!fileUploadRequest.getCoverFlag() && file.exists()) {
            return new FileUploadResponse(2, "文件已存在");
        }

        //如果目录不存在，新建目录
        if (!file.getParentFile().exists()) {
            file.getParentFile().mkdirs();
        }

        String fileMD5 = DigestUtils.md5Hex(file.getAbsolutePath());
        //上传文件
        try {
            // 保存分片文件
            File chunkFile = new File(fileServerPath + "tmp/" + fileMD5 + ".part" + fileUploadRequest.getChunkIndex());
            fileUploadRequest.getFile().transferTo(chunkFile);

            // 如果所有分片都上传完成，则合并文件
            if (fileUploadRequest.getChunkIndex() == fileUploadRequest.getTotalChunks() - 1) {
                mergeChunks(file, fileUploadRequest.getTotalChunks());
                //上传完成后执行后置操作
                fileAddAfter(file);
            }
        }catch (Exception e) {
            log.error("上传文件异常", e);
            //遍历删除分片
            for (int i = 0; i < fileUploadRequest.getTotalChunks(); i++) {
                File chunkFile = new File(fileServerPath + "tmp/" + fileMD5 + ".part" + fileUploadRequest.getChunkIndex());
                if (chunkFile.exists()) {
                    chunkFile.delete();
                }
            }
            //删除上传目录的文件
            if (file.exists()) {
                file.delete();
            }
            throw new ServiceException(HttpStatus.ERROR, "上传文件异常");
        }

        return new FileUploadResponse(1, "上传成功");
    }

    // 合并所有分片
    private void mergeChunks(File file, int totalChunks) throws IOException {
        //从绝对路径计算MD5
        String fileMD5 = DigestUtils.md5Hex(file.getAbsolutePath());

        try (FileOutputStream fileOutputStream = new FileOutputStream(file);) {
            for (int i = 0; i < totalChunks; i++) {
                // 分片文件
                File chunkFile = new File(fileServerPath + "tmp/" + fileMD5 + ".part" + i);
                FileInputStream chunkInputStream = new FileInputStream(chunkFile);

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = chunkInputStream.read(buffer)) != -1) {
                    fileOutputStream.write(buffer, 0, bytesRead);
                }

                chunkInputStream.close();
                chunkFile.delete();  // 删除分片文件
            }
        }
    }

    @Override
    public void addFileInk(AddFileInkRequest addFileInkRequest) throws IOException {
        if (addFileInkRequest.getOpenType() == 0 && StringUtils.isBlank(addFileInkRequest.getUserId())) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "添加私人文件时用户id不能为空");
        }

        //根据openType获取目录
        String fileDirectory = addFileInkRequest.getOpenType() == 1 ?
                fileServerPath + PUBLIC_DIRECTORY :
                fileServerPath + PRIVATE_DIRECTORY + addFileInkRequest.getUserId();

        // 拼接完整地址
        String filePath = fileDirectory + "/" + addFileInkRequest.getFilePath().substring(1) + "/" + addFileInkRequest.getFileName();
        File file = new File(filePath);
        if (file.exists()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "目录下存在同名文件，无法同步至该目录");
        } else {
            // 判断源路径是否是文件夹
            Path target = Paths.get(addFileInkRequest.getInkFilePath());
            Path link = Paths.get(file.getAbsolutePath());

            if (Files.isDirectory(target)) {
                // 如果源文件是文件夹，递归处理文件夹中的文件
                createSymbolicLinksForDirectory(link, target);
            } else {
                // 如果目录不存在则创建
                Path linkParent = link.getParent();
                if (Files.notExists(linkParent)) {
                    Files.createDirectories(linkParent);
                }
                // 如果源路径是文件，直接创建符号链接
                Files.createSymbolicLink(link, target);
            }
        }
    }

    // 递归处理文件夹并创建符号链接
    private void createSymbolicLinksForDirectory(Path link, Path target) throws IOException {
        if (Files.notExists(link)) {
            Files.createDirectories(link);  // 创建目标文件夹
        }

        // 遍历源文件夹中的所有文件和子文件夹
        try (Stream<Path> paths = Files.walk(target)) {
            paths.filter(Files::isRegularFile)  // 只处理文件
                    .forEach(sourceFile -> {
                        try {
                            Path relativePath = target.relativize(sourceFile);  // 获取相对路径
                            Path linkFile = link.resolve(relativePath);    // 目标路径

                            // 确保目标目录存在
                            Path parentDir = linkFile.getParent();
                            if (Files.notExists(parentDir)) {
                                Files.createDirectories(parentDir);  // 创建父目录
                            }

                            // 创建符号链接
                            Files.createSymbolicLink(linkFile, sourceFile);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    @Override
    public Boolean createDirectory(FileRequestDto fileRequestDto) {
        String fileDirectory = fileRequestDto.getOpenType() == 1 ?
                fileServerPath + PUBLIC_DIRECTORY :
                fileServerPath + PRIVATE_DIRECTORY + LoginMessageUtil.getLoginUser().get().getUserId();

        File file = new File(fileDirectory + fileRequestDto.getFilePath());

        if (file.exists()) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "同名目录或文件已存在，无法创建");
        }else {
            return file.mkdirs();
        }
    }

    @Override
    public void moveFile(FileRenameRequestDto fileRenameRequestDto) {
        String fileDirectory = fileRenameRequestDto.getOpenType() == 1 ?
                fileServerPath + PUBLIC_DIRECTORY :
                fileServerPath + PRIVATE_DIRECTORY + LoginMessageUtil.getLoginUser().get().getUserId() + "/";

        File file = new File(fileDirectory + fileRenameRequestDto.getOriginFilePath());

        boolean isSuccess = false;
        if (file.exists()) {
            File newFile = new File(fileDirectory + fileRenameRequestDto.getNewFilePath());
            isSuccess = file.renameTo(newFile);

            //执行原文件删除后置操作
            fileDeleteAfter(file);
            //执行新文件添加的后置操作
            fileAddAfter(newFile);
        }else {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "原文件不存在");
        }

        //如果不成功，抛出异常
        if (!isSuccess) {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "移动失败，新的位置存在同名文件");
        }
    }

    @Override
    public Boolean deleteFile(FileRequestDto fileRequestDto) {
        String fileDirectory = fileRequestDto.getOpenType() == 1 ?
                fileServerPath + PUBLIC_DIRECTORY :
                fileServerPath + PRIVATE_DIRECTORY + LoginMessageUtil.getLoginUser().get().getUserId();

        File file = new File(fileDirectory + fileRequestDto.getFilePath());

        //如果文件存在，执行删除
        if (file.exists()) {
            return deleteFile(file);
        }

        return false;
    }

    /**
     * 删除文件或递归删除目录
     */
    private Boolean deleteFile(File deleteFile) {
        boolean isSuccess = true;

        //如果不是符号链接，且是文件夹，递归删除内部文件
        if (!Files.isSymbolicLink(deleteFile.toPath()) && deleteFile.isDirectory()) {
            for (File subFile : deleteFile.listFiles()) {
                deleteFile(subFile);
            }
        }

        //删除文件
        isSuccess = isSuccess && deleteFile.delete();
        if (isSuccess) {
            //执行文件删除后置操作
            fileDeleteAfter(deleteFile);
        }

        return isSuccess;
    }

    /**
     * 文件在http中响应
     */
    @SneakyThrows
    private void writeFileToResponse(HttpServletResponse response, HttpServletRequest request, File file) {
        if (file.exists()) {
            if (file.isDirectory()) {
                File tmpFile = new File(fileServerPath + "tmp/" + System.currentTimeMillis() + ".zip");
                try (FileOutputStream tmpFileOutputStream = new FileOutputStream(tmpFile)){
                    ZipUtils.toZip(file, tmpFileOutputStream);
                    //将下载文件指向压缩后的临时文件
                    file = tmpFile;
                }
            }
            //生成文件唯一标识
            String eTag = generateETag(file);

            String contentType;
            try {
                // 根据文件路径探测 MIME 类型
                contentType = Files.probeContentType(file.toPath());
                if (contentType == null) {
                    // 默认二进制流类型
                    contentType = "application/octet-stream";
                }
            } catch (IOException e) {
                contentType = "application/octet-stream";
            }
            //设置部分响应信息
            response.setContentType(MediaType.parseMediaType(contentType).toString());
            response.setCharacterEncoding("utf-8");
            response.setHeader("Content-disposition", "attachment;filename="+ URLEncoder.encode(file.getName(), StandardCharsets.UTF_8));
            //缓存24小时
            response.setHeader("Cache-Control", "max-age=86400");
            //文件资源唯一标识
            response.setHeader("ETag", eTag);

            //获取Range字段
            String rangeString = request.getHeader("Range");
            if (StringUtils.isNotBlank(rangeString)) {
                //如果范围存在，设定文件读取开始位置后输出
                try (RandomAccessFile targetFile = new RandomAccessFile(file, "r")){
                    // Parse the Range header
                    HttpRange range = HttpRange.parseRanges(rangeString).get(0);
                    long rangeStart = range.getRangeStart(targetFile.length());
                    long rangeEnd = Math.min(range.getRangeEnd(targetFile.length()), targetFile.length() - 1);

                    //设置此次相应返回的数据长度
                    response.setHeader("Content-Length", String.valueOf(rangeEnd - rangeStart + 1));
                    response.setHeader("Accept-Ranges", "bytes");
                    //设置此次相应返回的数据范围
                    response.setHeader("Content-Range", "bytes " + rangeStart + "-" + rangeEnd + "/" + targetFile.length());
                    //返回码需要为206，而不是200
                    response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                    //设定文件读取开始位置（以字节为单位）
                    targetFile.seek(rangeStart);
                    byte[] b = new byte[8192];
                    int length;
                    long bytesRead = 0;

                    try {
                        while ((length = targetFile.read(b)) > 0) {
                            // 调整最后一个块的大小
                            if (bytesRead + length > (rangeEnd - rangeStart + 1)) {
                                length = (int) (rangeEnd - rangeStart + 1 - bytesRead);
                            }
                            response.getOutputStream().write(b, 0, length);
                            bytesRead += length;

                            // 如果已读取完range长度的内容，则停止
                            if (bytesRead >= (rangeEnd - rangeStart + 1)) {
                                break;
                            }
                        }
                        response.getOutputStream().flush();
                    }catch (IOException e) {
                        // tomcat原话。写操作IO异常几乎总是由于客户端主动关闭连接导致，所以直接吃掉异常打日志
                        //比如使用video播放视频时经常会发送Range为0- 的范围只是为了获取视频大小，之后就中断连接了
                    }
                }catch (Exception e) {
                    log.error("读取文件失败", e);
                }
            }else {
                //设置此次相应返回的数据长度
                response.setHeader("Content-Length", String.valueOf(file.length()));

                try (BufferedInputStream fileInputStream = new BufferedInputStream(new FileInputStream(file));){
                    byte[] b = new byte[8192];
                    int length;
                    try {
                        while ((length = fileInputStream.read(b)) > 0) {
                            response.getOutputStream().write(b, 0, length);
                        }
                        response.getOutputStream().flush();
                    }catch (IOException e) {
                        // tomcat原话。写操作IO异常几乎总是由于客户端主动关闭连接导致，所以直接吃掉异常打日志
                        //比如使用video播放视频时经常会发送Range为0- 的范围只是为了获取视频大小，之后就中断连接了
                    }
                }catch (Exception e) {
                    log.error("读取文件失败", e);
                }
            }


        }else {
            throw new ServiceException(HttpStatus.BAD_REQUEST, "文件不存在");
        }
    }

    /**
     * 生成弱Etag标识
     */
    private String generateETag(File file) {
        if (!file.exists() || !file.isFile()) {
            return null;  // 文件不存在或不是一个文件时返回 null
        }
        long lastModified = file.lastModified();  // 获取最后修改时间
        long fileSize = file.length();            // 获取文件大小

        String fileTag = String.valueOf(lastModified) + String.valueOf(fileSize);
        // 生成 ETag，将最后修改时间和文件大小组合，使用 MD5 编码
        return "W/\"" + DigestUtils.md5Hex(fileTag) + "\"";
    }

    /**
     * 文件上传后置操作
     */
    private void fileAddAfter(File uploadFile) {
        //如果文件是图片，生成缩略图
        if (FileType.IMAGE_FILE.equals(FileTypeUtils.getFileType(uploadFile))) {
            previewService.generatePreviewFile(uploadFile);
        }
    }

    /**
     * 文件删除后置操作
     */
    private void fileDeleteAfter(File deleteFile) {
        //删除预览图片
        previewService.deletePreviewFile(deleteFile);
    }
}
