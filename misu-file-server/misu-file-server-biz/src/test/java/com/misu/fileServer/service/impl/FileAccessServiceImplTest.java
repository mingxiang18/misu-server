package com.misu.fileServer.service.impl;

import com.misu.common.exception.ServiceException;
import com.misu.fileServer.constant.VideoTranscodeState;
import com.misu.fileServer.domain.dto.FileDownloadRequestDto;
import com.misu.fileServer.domain.dto.FileRequestDto;
import com.misu.fileServer.domain.dto.VideoTranscodeStatusDto;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.fileServer.repository.FileMappingRepository;
import com.misu.fileServer.service.PreviewService;
import com.misu.fileServer.service.VideoTranscodeService;
import com.misu.fileServer.service.support.FilePathResolver;
import com.misu.fileServer.service.support.PhysicalFileOps;
import com.misu.framework.web.HttpFileResponder;
import com.misu.security.dto.LoginUser;
import com.misu.security.service.TokenService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FileAccessServiceImpl} 边界单测（纯 JUnit5 + Mockito + {@link TempDir}）。
 *
 * <p>策略：
 * <ul>
 *   <li>关键下载 / 流式路径用 <b>真实</b> {@link HttpFileResponder}，断言真实 Range/206、304、Content-Type；</li>
 *   <li>跨用户 / 鉴权 / 转码状态分支用 <b>mock</b> {@link HttpFileResponder}，verify 调用参数（attachment / file / mimeType）；</li>
 *   <li>{@link FilePathResolver} 全程 mock（其内部依赖 SecurityContext，单独单测覆盖）；</li>
 *   <li>{@link TokenService}/{@link PreviewService}/{@link VideoTranscodeService}/{@link FileMappingRepository}/
 *       {@link PhysicalFileOps} 全 mock；登录态用 {@link SecurityContextHolder} 注入 {@link LoginUser}。</li>
 * </ul></p>
 */
@ExtendWith(MockitoExtension.class)
class FileAccessServiceImplTest {

    @TempDir
    Path tempDir;

    @Mock
    TokenService tokenService;
    @Mock
    PreviewService previewService;
    @Mock
    VideoTranscodeService videoTranscodeService;
    @Mock
    FileMappingRepository fileMappingRepository;
    @Mock
    FilePathResolver filePathResolver;
    @Mock
    PhysicalFileOps physicalFileOps;

    private FileAccessServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FileAccessServiceImpl();
        ReflectionTestUtils.setField(service, "fileServerPath", tempDir.toString() + "/");
        ReflectionTestUtils.setField(service, "tokenExpireTtl", 86400000L);
        ReflectionTestUtils.setField(service, "tokenService", tokenService);
        ReflectionTestUtils.setField(service, "previewService", previewService);
        ReflectionTestUtils.setField(service, "videoTranscodeService", videoTranscodeService);
        ReflectionTestUtils.setField(service, "fileMappingRepository", fileMappingRepository);
        ReflectionTestUtils.setField(service, "filePathResolver", filePathResolver);
        ReflectionTestUtils.setField(service, "physicalFileOps", physicalFileOps);
        // 默认注入真实 responder；需要 verify 的用例各自 override 成 mock
        ReflectionTestUtils.setField(service, "httpFileResponder", new HttpFileResponder());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ----- helpers -----

    private void login(long userId) {
        LoginUser loginUser = new LoginUser(userId, "u" + userId, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }

    private HttpFileResponder useMockResponder() {
        HttpFileResponder mockResponder = mock(HttpFileResponder.class);
        ReflectionTestUtils.setField(service, "httpFileResponder", mockResponder);
        return mockResponder;
    }

    private File writeFile(String name, String content) throws Exception {
        Path p = tempDir.resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p.toFile();
    }

    /** 写一个最小合法 PNG，使 Files.probeContentType → image/png。 */
    private File writePng(String name) throws Exception {
        // 1x1 透明 PNG
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");
        Path p = tempDir.resolve(name);
        Files.write(p, png);
        return p.toFile();
    }

    private FileMapping mapping(int openType, String userId, String virtualPath, File target) {
        FileMapping m = new FileMapping();
        m.setOpenType(openType);
        m.setUserId(userId);
        m.setVirtualPath(virtualPath);
        m.setTargetPath(target.getAbsolutePath());
        return m;
    }

    // ===================== getFileDownloadLink =====================

    @Test
    void getFileDownloadLink_privateFile_generatesTokenLink() throws Exception {
        login(7L);
        File f = writeFile("a.txt", "hi");
        FileRequestDto dto = new FileRequestDto("a.txt", 0);
        when(filePathResolver.resolveUserRequestFile(dto)).thenReturn(f.toPath());
        when(tokenService.createToken(any())).thenReturn("TOKEN123");

        String link = service.getFileDownloadLink(dto);

        assertEquals("fileServer/file/downloadFile?fileToken=TOKEN123", link);
        // claims 应包含 userId + 私有路径 private/7/a.txt
        ArgumentCaptor<java.util.Map<String, Object>> captor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(tokenService).createToken(captor.capture());
        assertEquals(7L, captor.getValue().get("userId"));
        assertEquals("private/7/a.txt", captor.getValue().get("filePath"));
    }

    @Test
    void getFileDownloadLink_publicFile_usesPublicPrefix() throws Exception {
        login(9L);
        File f = writeFile("pub.txt", "x");
        FileRequestDto dto = new FileRequestDto("pub.txt", 1);
        when(filePathResolver.resolveUserRequestFile(dto)).thenReturn(f.toPath());
        when(tokenService.createToken(any())).thenReturn("PUBTOK");

        String link = service.getFileDownloadLink(dto);

        assertEquals("fileServer/file/downloadFile?fileToken=PUBTOK", link);
        ArgumentCaptor<java.util.Map<String, Object>> captor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(tokenService).createToken(captor.capture());
        assertEquals("public/pub.txt", captor.getValue().get("filePath"));
    }

    @Test
    void getFileDownloadLink_missingFile_throwsBadRequest() {
        login(1L);
        FileRequestDto dto = new FileRequestDto("ghost.txt", 0);
        when(filePathResolver.resolveUserRequestFile(dto)).thenReturn(tempDir.resolve("ghost.txt"));

        assertThrows(ServiceException.class, () -> service.getFileDownloadLink(dto));
    }

    // ===================== downloadFile (token) =====================

    @Test
    void downloadFile_validPublicToken_writesFile() throws Exception {
        File f = writeFile("doc.txt", "content-bytes");
        Date future = new Date(System.currentTimeMillis() + 60_000);
        Claims claims = mock(Claims.class);
        when(claims.getExpiration()).thenReturn(future);
        when(claims.get("filePath", String.class)).thenReturn("public/doc.txt");
        when(tokenService.parseToken("OK")).thenReturn(claims);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(1, "public", "doc.txt"))
                .thenReturn(Optional.of(mapping(1, "public", "doc.txt", f)));
        when(filePathResolver.resolveMappedFile(any())).thenReturn(f);

        FileDownloadRequestDto req = new FileDownloadRequestDto();
        req.setFileToken("OK");
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.downloadFile(req, request, response);

        assertEquals(200, response.getStatus());
        assertEquals("content-bytes", response.getContentAsString());
        assertTrue(response.getHeader("Content-disposition").startsWith("attachment"));
        assertNotNull(response.getHeader("ETag"));
    }

    @Test
    void downloadFile_expiredToken_throwsForbidden() {
        Date past = new Date(System.currentTimeMillis() - 60_000);
        Claims claims = mock(Claims.class);
        when(claims.getExpiration()).thenReturn(past);
        when(tokenService.parseToken("EXP")).thenReturn(claims);

        FileDownloadRequestDto req = new FileDownloadRequestDto();
        req.setFileToken("EXP");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.downloadFile(req, new MockHttpServletRequest(), new MockHttpServletResponse()));
        assertEquals(com.misu.common.constant.HttpStatus.FORBIDDEN, ex.getCode());
    }

    @Test
    void downloadFile_parseFailure_throwsForbidden() {
        when(tokenService.parseToken("BAD")).thenThrow(new RuntimeException("boom"));
        FileDownloadRequestDto req = new FileDownloadRequestDto();
        req.setFileToken("BAD");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.downloadFile(req, new MockHttpServletRequest(), new MockHttpServletResponse()));
        assertEquals(com.misu.common.constant.HttpStatus.FORBIDDEN, ex.getCode());
    }

    @Test
    void downloadFile_privateTokenUserMismatch_throwsForbidden() {
        Date future = new Date(System.currentTimeMillis() + 60_000);
        Claims claims = mock(Claims.class);
        when(claims.getExpiration()).thenReturn(future);
        when(claims.get("filePath", String.class)).thenReturn("private/5/a.txt");
        when(claims.get("userId")).thenReturn("999"); // 与路径里的 5 不符
        when(tokenService.parseToken("MM")).thenReturn(claims);

        FileDownloadRequestDto req = new FileDownloadRequestDto();
        req.setFileToken("MM");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.downloadFile(req, new MockHttpServletRequest(), new MockHttpServletResponse()));
        assertEquals(com.misu.common.constant.HttpStatus.FORBIDDEN, ex.getCode());
    }

    @Test
    void downloadFile_validTokenButFileGone_throwsBadRequest() {
        Date future = new Date(System.currentTimeMillis() + 60_000);
        Claims claims = mock(Claims.class);
        when(claims.getExpiration()).thenReturn(future);
        when(claims.get("filePath", String.class)).thenReturn("public/gone.txt");
        when(tokenService.parseToken("G")).thenReturn(claims);
        File ghost = tempDir.resolve("gone.txt").toFile();
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(1, "public", "gone.txt"))
                .thenReturn(Optional.of(mapping(1, "public", "gone.txt", ghost)));
        when(filePathResolver.resolveMappedFile(any())).thenReturn(ghost);

        FileDownloadRequestDto req = new FileDownloadRequestDto();
        req.setFileToken("G");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.downloadFile(req, new MockHttpServletRequest(), new MockHttpServletResponse()));
        assertEquals(com.misu.common.constant.HttpStatus.BAD_REQUEST, ex.getCode());
    }

    // ===================== accessUserFile (real responder: Range/304) =====================

    @Test
    void accessUserFile_attachmentTrue_writesAsAttachment() throws Exception {
        File f = writeFile("dl.txt", "0123456789");
        FileRequestDto dto = new FileRequestDto("dl.txt", 0);
        when(filePathResolver.resolveUserRequestFile(dto)).thenReturn(f.toPath());

        MockHttpServletResponse response = new MockHttpServletResponse();
        service.accessUserFile(dto, new MockHttpServletRequest(), response, true);

        assertEquals(200, response.getStatus());
        assertEquals("0123456789", response.getContentAsString());
        assertTrue(response.getHeader("Content-disposition").startsWith("attachment"));
    }

    @Test
    void accessUserFile_rangeRequest_returns206Partial() throws Exception {
        File f = writeFile("range.txt", "0123456789");
        FileRequestDto dto = new FileRequestDto("range.txt", 0);
        when(filePathResolver.resolveUserRequestFile(dto)).thenReturn(f.toPath());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Range", "bytes=2-5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.accessUserFile(dto, request, response, false);

        assertEquals(206, response.getStatus());
        assertEquals("2345", response.getContentAsString());
        assertEquals("bytes 2-5/10", response.getHeader("Content-Range"));
        assertTrue(response.getHeader("Content-disposition").startsWith("inline"));
    }

    @Test
    void accessUserFile_ifNoneMatchHit_returns304() throws Exception {
        File f = writeFile("etag.txt", "abc");
        FileRequestDto dto = new FileRequestDto("etag.txt", 0);
        when(filePathResolver.resolveUserRequestFile(dto)).thenReturn(f.toPath());

        // 先取一次拿到 ETag
        MockHttpServletResponse first = new MockHttpServletResponse();
        service.accessUserFile(dto, new MockHttpServletRequest(), first, false);
        String etag = first.getHeader("ETag");
        assertNotNull(etag);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("If-None-Match", etag);
        MockHttpServletResponse second = new MockHttpServletResponse();
        service.accessUserFile(dto, request, second, false);

        assertEquals(304, second.getStatus());
    }

    @Test
    void accessUserFile_missingFile_throwsBadRequest() {
        FileRequestDto dto = new FileRequestDto("nope.txt", 0);
        when(filePathResolver.resolveUserRequestFile(dto)).thenReturn(tempDir.resolve("nope.txt"));

        assertThrows(ServiceException.class,
                () -> service.accessUserFile(dto, new MockHttpServletRequest(), new MockHttpServletResponse(), false));
    }

    @Test
    void accessUserFile_contentTypeMatchesProbe() throws Exception {
        File png = writePng("pic.png");
        FileRequestDto dto = new FileRequestDto("pic.png", 0);
        when(filePathResolver.resolveUserRequestFile(dto)).thenReturn(png.toPath());

        MockHttpServletResponse response = new MockHttpServletResponse();
        service.accessUserFile(dto, new MockHttpServletRequest(), response, false);

        assertEquals(200, response.getStatus());
        // 与原 writeFileToResponse 一样走 Files.probeContentType；PNG 应识别为 image/png
        assertEquals("image/png", Files.probeContentType(png.toPath()));
        assertTrue(response.getContentType().startsWith("image/png"));
    }

    // ===================== accessUserFileAsUser (mock responder verify attachment) =====================

    @Test
    void accessUserFileAsUser_passesAttachmentFlagToResponder() throws Exception {
        HttpFileResponder responder = useMockResponder();
        File f = writeFile("as.txt", "data");
        when(filePathResolver.resolveUserRequestFile(0, "5", "as.txt")).thenReturn(f.toPath());

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.accessUserFileAsUser(0, "5", "as.txt", request, response, false);

        ArgumentCaptor<Boolean> attach = ArgumentCaptor.forClass(Boolean.class);
        verify(responder).write(eq(request), eq(response), eq(f), eq(f.getName()), anyString(), attach.capture());
        assertFalse(attach.getValue());
    }

    @Test
    void accessUserFileAsUser_attachmentTrue_flagTrue() throws Exception {
        HttpFileResponder responder = useMockResponder();
        File f = writeFile("as2.txt", "data");
        when(filePathResolver.resolveUserRequestFile(0, "5", "as2.txt")).thenReturn(f.toPath());

        service.accessUserFileAsUser(0, "5", "as2.txt", new MockHttpServletRequest(), new MockHttpServletResponse(), true);

        ArgumentCaptor<Boolean> attach = ArgumentCaptor.forClass(Boolean.class);
        verify(responder).write(any(), any(), eq(f), anyString(), anyString(), attach.capture());
        assertTrue(attach.getValue());
    }

    @Test
    void accessUserFileAsUser_missingFile_throwsBadRequest() {
        when(filePathResolver.resolveUserRequestFile(0, "5", "x.txt")).thenReturn(tempDir.resolve("x.txt"));
        assertThrows(ServiceException.class,
                () -> service.accessUserFileAsUser(0, "5", "x.txt", new MockHttpServletRequest(), new MockHttpServletResponse(), false));
    }

    // ===================== previewFile =====================

    @Test
    void previewFile_existingThumbnail_writesPreview() throws Exception {
        HttpFileResponder responder = useMockResponder();
        File origin = writePng("p.png");
        File preview = writePng("p-thumb.png");
        FileRequestDto dto = new FileRequestDto("p.png", 0);
        when(filePathResolver.resolveUserRequestFile(dto)).thenReturn(origin.toPath());
        when(filePathResolver.getPreviewFile(origin)).thenReturn(preview);

        service.previewFile(dto, new MockHttpServletRequest(), new MockHttpServletResponse());

        verify(responder).write(any(), any(), eq(preview), anyString(), anyString(), eq(false));
    }

    @Test
    void previewFile_noThumbnail_generatesThenWritesOrigin() throws Exception {
        HttpFileResponder responder = useMockResponder();
        File origin = writePng("q.png");
        File missingPreview = tempDir.resolve("q-thumb.png").toFile();
        FileRequestDto dto = new FileRequestDto("q.png", 0);
        when(filePathResolver.resolveUserRequestFile(dto)).thenReturn(origin.toPath());
        when(filePathResolver.getPreviewFile(origin)).thenReturn(missingPreview);

        service.previewFile(dto, new MockHttpServletRequest(), new MockHttpServletResponse());

        // 缩略图不存在 → 触发生成 + 回退写原图
        verify(previewService).generatePreviewFile(origin);
        verify(responder).write(any(), any(), eq(origin), anyString(), anyString(), eq(false));
    }

    @Test
    void previewFile_nonImage_throwsBadRequest() throws Exception {
        File txt = writeFile("note.txt", "hello");
        FileRequestDto dto = new FileRequestDto("note.txt", 0);
        when(filePathResolver.resolveUserRequestFile(dto)).thenReturn(txt.toPath());

        assertThrows(ServiceException.class,
                () -> service.previewFile(dto, new MockHttpServletRequest(), new MockHttpServletResponse()));
    }

    @Test
    void previewFile_missingFile_throwsBadRequest() {
        FileRequestDto dto = new FileRequestDto("x.png", 0);
        when(filePathResolver.resolveUserRequestFile(dto)).thenReturn(tempDir.resolve("x.png"));
        assertThrows(ServiceException.class,
                () -> service.previewFile(dto, new MockHttpServletRequest(), new MockHttpServletResponse()));
    }

    // ===================== videoPreviewFile =====================

    @Test
    void videoPreviewFile_coverExists_writesCover() throws Exception {
        HttpFileResponder responder = useMockResponder();
        File video = writeFile("v.mp4", "fake-video");
        File cover = writePng("v-cover.png");
        FileRequestDto dto = new FileRequestDto("v.mp4", 0);
        when(filePathResolver.resolveUserRequestFile(dto)).thenReturn(video.toPath());
        when(videoTranscodeService.getVideoPreviewFile(video)).thenReturn(cover);

        service.videoPreviewFile(dto, new MockHttpServletRequest(), new MockHttpServletResponse());

        verify(responder).write(any(), any(), eq(cover), anyString(), anyString(), eq(false));
    }

    @Test
    void videoPreviewFile_coverMissing_throwsBadRequest() throws Exception {
        File video = writeFile("v2.mp4", "fake");
        File missingCover = tempDir.resolve("v2-cover.png").toFile();
        FileRequestDto dto = new FileRequestDto("v2.mp4", 0);
        when(filePathResolver.resolveUserRequestFile(dto)).thenReturn(video.toPath());
        when(videoTranscodeService.getVideoPreviewFile(video)).thenReturn(missingCover);

        assertThrows(ServiceException.class,
                () -> service.videoPreviewFile(dto, new MockHttpServletRequest(), new MockHttpServletResponse()));
    }

    @Test
    void videoPreviewFile_nonVideo_throwsBadRequest() throws Exception {
        File txt = writeFile("doc.txt", "x");
        FileRequestDto dto = new FileRequestDto("doc.txt", 0);
        when(filePathResolver.resolveUserRequestFile(dto)).thenReturn(txt.toPath());

        assertThrows(ServiceException.class,
                () -> service.videoPreviewFile(dto, new MockHttpServletRequest(), new MockHttpServletResponse()));
    }

    // ===================== transcodedVideoFile / transcodedVideoFileAsUser =====================

    private VideoTranscodeStatusDto status(String state, String message) {
        VideoTranscodeStatusDto dto = new VideoTranscodeStatusDto();
        dto.setState(state);
        dto.setMessage(message);
        return dto;
    }

    @Test
    void transcodedVideoFile_success_writesTranscoded() throws Exception {
        HttpFileResponder responder = useMockResponder();
        File video = writeFile("clip.mp4", "src");
        File transcoded = writeFile("clip-h265.mp4", "transcoded");
        FileRequestDto dto = new FileRequestDto("clip.mp4", 0);
        when(filePathResolver.resolveUserRequestFile(dto)).thenReturn(video.toPath());
        when(videoTranscodeService.getOrCreateTranscodeStatus(video)).thenReturn(status(VideoTranscodeState.SUCCESS, null));
        when(videoTranscodeService.getTranscodedFile(video)).thenReturn(transcoded);

        service.transcodedVideoFile(dto, new MockHttpServletRequest(), new MockHttpServletResponse());

        verify(responder).write(any(), any(), eq(transcoded), anyString(), anyString(), eq(false));
    }

    @Test
    void transcodedVideoFile_passthrough_writesOrigin() throws Exception {
        HttpFileResponder responder = useMockResponder();
        File video = writeFile("pt.mp4", "src");
        FileRequestDto dto = new FileRequestDto("pt.mp4", 0);
        when(filePathResolver.resolveUserRequestFile(dto)).thenReturn(video.toPath());
        when(videoTranscodeService.getOrCreateTranscodeStatus(video)).thenReturn(status(VideoTranscodeState.PASSTHROUGH, null));

        service.transcodedVideoFile(dto, new MockHttpServletRequest(), new MockHttpServletResponse());

        verify(responder).write(any(), any(), eq(video), anyString(), anyString(), eq(false));
    }

    @Test
    void transcodedVideoFile_notSuccess_throwsBadRequest() throws Exception {
        File video = writeFile("wip.mp4", "src");
        FileRequestDto dto = new FileRequestDto("wip.mp4", 0);
        when(filePathResolver.resolveUserRequestFile(dto)).thenReturn(video.toPath());
        when(videoTranscodeService.getOrCreateTranscodeStatus(video))
                .thenReturn(status(VideoTranscodeState.PROCESSING, "转码中"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.transcodedVideoFile(dto, new MockHttpServletRequest(), new MockHttpServletResponse()));
        assertTrue(ex.getMessage().contains("转码中"));
    }

    @Test
    void transcodedVideoFile_successButTranscodedMissing_throwsBadRequest() throws Exception {
        File video = writeFile("miss.mp4", "src");
        File missingTranscoded = tempDir.resolve("miss-h265.mp4").toFile();
        FileRequestDto dto = new FileRequestDto("miss.mp4", 0);
        when(filePathResolver.resolveUserRequestFile(dto)).thenReturn(video.toPath());
        when(videoTranscodeService.getOrCreateTranscodeStatus(video)).thenReturn(status(VideoTranscodeState.SUCCESS, null));
        when(videoTranscodeService.getTranscodedFile(video)).thenReturn(missingTranscoded);

        assertThrows(ServiceException.class,
                () -> service.transcodedVideoFile(dto, new MockHttpServletRequest(), new MockHttpServletResponse()));
    }

    @Test
    void transcodedVideoFileAsUser_success_writesTranscoded() throws Exception {
        HttpFileResponder responder = useMockResponder();
        File video = writeFile("room.mp4", "src");
        File transcoded = writeFile("room-h265.mp4", "t");
        when(filePathResolver.resolveUserRequestFile(0, "3", "room.mp4")).thenReturn(video.toPath());
        when(videoTranscodeService.getOrCreateTranscodeStatus(video)).thenReturn(status(VideoTranscodeState.SUCCESS, null));
        when(videoTranscodeService.getTranscodedFile(video)).thenReturn(transcoded);

        service.transcodedVideoFileAsUser(0, "3", "room.mp4", new MockHttpServletRequest(), new MockHttpServletResponse());

        verify(responder).write(any(), any(), eq(transcoded), anyString(), anyString(), eq(false));
    }

    // ===================== existsUserFile =====================

    @Test
    void existsUserFile_fileExists_true() {
        File f = tempDir.resolve("e.txt").toFile();
        assertTrue(touch(f));
        when(filePathResolver.getMappingUserId(0, "5")).thenReturn("5");
        when(filePathResolver.resolveUserRequestFile(0, "5", "e.txt")).thenReturn(f.toPath());

        assertTrue(service.existsUserFile(0, "5", "e.txt", false));
    }

    @Test
    void existsUserFile_fileMissing_false() {
        when(filePathResolver.getMappingUserId(0, "5")).thenReturn("5");
        when(filePathResolver.resolveUserRequestFile(0, "5", "no.txt")).thenReturn(tempDir.resolve("no.txt"));

        assertFalse(service.existsUserFile(0, "5", "no.txt", false));
    }

    @Test
    void existsUserFile_directoryButAllowDirectoryFalse_false() {
        File dir = tempDir.resolve("somedir").toFile();
        assertTrue(dir.mkdirs());
        when(filePathResolver.getMappingUserId(0, "5")).thenReturn("5");
        when(filePathResolver.resolveUserRequestFile(0, "5", "somedir")).thenReturn(dir.toPath());

        assertFalse(service.existsUserFile(0, "5", "somedir", false));
    }

    @Test
    void existsUserFile_directoryMappingExists_allowDirectoryTrue() {
        File dir = tempDir.resolve("mydir").toFile();
        assertTrue(dir.mkdirs());
        when(filePathResolver.getMappingUserId(0, "5")).thenReturn("5");
        FileMapping dirMapping = mapping(0, "5", "mydir", dir);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, "5", "mydir"))
                .thenReturn(Optional.of(dirMapping));
        when(filePathResolver.resolveMappedFile(dirMapping)).thenReturn(dir);

        assertTrue(service.existsUserFile(0, "5", "mydir", true));
    }

    @Test
    void existsUserFile_resolveThrows_false() {
        when(filePathResolver.getMappingUserId(0, "5")).thenReturn("5");
        when(filePathResolver.resolveUserRequestFile(0, "5", "boom.txt"))
                .thenThrow(new ServiceException(com.misu.common.constant.HttpStatus.BAD_REQUEST, "x"));

        assertFalse(service.existsUserFile(0, "5", "boom.txt", false));
    }

    private boolean touch(File f) {
        try {
            return f.createNewFile();
        } catch (Exception e) {
            return false;
        }
    }

    // ===================== downloadDirectoryAsZip =====================

    @Test
    void downloadDirectoryAsZip_streamsZipWithEntries() throws Exception {
        login(5L);
        // 物理文件
        File a = writeFile("a.txt", "AAAA");
        File b = writeFile("b.txt", "BBBBBB");

        when(filePathResolver.getMappingUserId(0, "5")).thenReturn("5");
        FileMapping root = mapping(0, "5", "docs", tempDir.toFile());
        root.setFileType(com.misu.fileServer.constant.FileType.DIRECTORY_FILE);
        root.setId(1L);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, "5", "docs"))
                .thenReturn(Optional.of(root));

        FileMapping fa = mapping(0, "5", "docs/a.txt", a);
        fa.setFileType(com.misu.fileServer.constant.FileType.TEXT_FILE);
        fa.setFileSize(4L);
        fa.setId(2L);
        FileMapping fb = mapping(0, "5", "docs/sub/b.txt", b);
        fb.setFileType(com.misu.fileServer.constant.FileType.TEXT_FILE);
        fb.setFileSize(6L);
        fb.setId(3L);
        FileMapping sub = mapping(0, "5", "docs/sub", tempDir.toFile());
        sub.setFileType(com.misu.fileServer.constant.FileType.DIRECTORY_FILE);
        sub.setId(4L);

        when(fileMappingRepository.findActiveSubtree(eq(0), eq("5"), eq("docs"), anyString()))
                .thenReturn(List.of(root, fa, sub, fb));
        when(physicalFileOps.getDirectoryDownloadMaxFiles()).thenReturn(1000L);
        when(physicalFileOps.getDirectoryDownloadMaxBytes()).thenReturn(209715200L);

        FileRequestDto dto = new FileRequestDto("docs", 0);
        MockHttpServletResponse response = new MockHttpServletResponse();
        service.downloadDirectoryAsZip(dto, response);

        assertTrue(response.getContentType().startsWith("application/zip"));
        byte[] zipBytes = response.getContentAsByteArray();
        assertTrue(zipBytes.length > 0);

        java.util.Set<String> entries = new java.util.HashSet<>();
        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                entries.add(e.getName());
            }
        }
        assertTrue(entries.contains("a.txt"), "entries=" + entries);
        assertTrue(entries.contains("sub/"), "entries=" + entries);
        assertTrue(entries.contains("sub/b.txt"), "entries=" + entries);
    }

    @Test
    void downloadDirectoryAsZip_notADirectory_throwsBadRequest() {
        login(5L);
        when(filePathResolver.getMappingUserId(0, "5")).thenReturn("5");
        FileMapping fileMapping = mapping(0, "5", "f.txt", tempDir.toFile());
        fileMapping.setFileType(com.misu.fileServer.constant.FileType.TEXT_FILE);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, "5", "f.txt"))
                .thenReturn(Optional.of(fileMapping));

        assertThrows(ServiceException.class,
                () -> service.downloadDirectoryAsZip(new FileRequestDto("f.txt", 0), new MockHttpServletResponse()));
    }

    @Test
    void downloadDirectoryAsZip_exceedsFileCountLimit_throwsBadRequest() {
        login(5L);
        when(filePathResolver.getMappingUserId(0, "5")).thenReturn("5");
        FileMapping root = mapping(0, "5", "big", tempDir.toFile());
        root.setFileType(com.misu.fileServer.constant.FileType.DIRECTORY_FILE);
        root.setId(1L);
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, "5", "big"))
                .thenReturn(Optional.of(root));

        FileMapping f1 = mapping(0, "5", "big/1.txt", tempDir.toFile());
        f1.setFileType(com.misu.fileServer.constant.FileType.TEXT_FILE);
        f1.setFileSize(1L);
        f1.setId(2L);
        FileMapping f2 = mapping(0, "5", "big/2.txt", tempDir.toFile());
        f2.setFileType(com.misu.fileServer.constant.FileType.TEXT_FILE);
        f2.setFileSize(1L);
        f2.setId(3L);
        when(fileMappingRepository.findActiveSubtree(eq(0), eq("5"), eq("big"), anyString()))
                .thenReturn(List.of(root, f1, f2));
        when(physicalFileOps.getDirectoryDownloadMaxFiles()).thenReturn(1L); // 2 > 1

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.downloadDirectoryAsZip(new FileRequestDto("big", 0), new MockHttpServletResponse()));
        assertTrue(ex.getMessage().contains("数量"));
    }

    @Test
    void downloadDirectoryAsZip_missingDirectory_throwsBadRequest() {
        login(5L);
        when(filePathResolver.getMappingUserId(0, "5")).thenReturn("5");
        when(fileMappingRepository.findFirstByOpenTypeAndUserIdAndVirtualPathAndDeletedFalse(0, "5", "ghost"))
                .thenReturn(Optional.empty());

        assertThrows(ServiceException.class,
                () -> service.downloadDirectoryAsZip(new FileRequestDto("ghost", 0), new MockHttpServletResponse()));
    }
}
