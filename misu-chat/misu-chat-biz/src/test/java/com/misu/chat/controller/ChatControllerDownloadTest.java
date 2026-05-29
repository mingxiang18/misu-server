package com.misu.chat.controller;

import com.misu.chat.domain.entity.ChatFile;
import com.misu.chat.service.ChatFileService;
import com.misu.chat.service.ConversationService;
import com.misu.framework.web.HttpFileResponder;
import com.misu.security.dto.LoginUser;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.File;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ChatController#downloadFile} 边界单测：纯 JUnit5 + Mockito，直接调用控制器方法。
 *
 * <p>{@code currentUserId()} 经 {@code LoginMessageUtil.getLoginUser()} 读 {@link SecurityContextHolder}，
 * 故直接往 SecurityContext 塞一个 {@link LoginUser} principal，无需 mockStatic。</p>
 */
@ExtendWith(MockitoExtension.class)
class ChatControllerDownloadTest {

    private static final Long FILE_ID = 100L;
    private static final Long CONV_ID = 7L;
    private static final String ME = "42";

    @Mock
    private ChatFileService chatFileService;
    @Mock
    private ConversationService conversationService;
    @Mock
    private HttpFileResponder httpFileResponder;

    @InjectMocks
    private ChatController controller;

    private MockHttpServletRequest req;
    private MockHttpServletResponse resp;

    @BeforeEach
    void setUp() {
        req = new MockHttpServletRequest();
        resp = new MockHttpServletResponse();
        // 登录态：userId=42
        LoginUser loginUser = new LoginUser(Long.parseLong(ME), "verifybot", Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ChatFile file(String category, boolean deleted) {
        ChatFile f = new ChatFile();
        f.setId(FILE_ID);
        f.setConversationId(CONV_ID);
        f.setCategory(category);
        f.setDeleted(deleted);
        return f;
    }

    private ChatFileService.FileDownload downloadDisk(File diskFile, String name, String mime) {
        ChatFileService.FileDownload d = new ChatFileService.FileDownload();
        d.diskFile = diskFile;
        d.fileName = name;
        d.mimeType = mime;
        return d;
    }

    private ChatFileService.FileDownload downloadBytes(byte[] bytes, String name, String mime) {
        ChatFileService.FileDownload d = new ChatFileService.FileDownload();
        d.bytes = bytes;
        d.fileName = name;
        d.mimeType = mime;
        return d;
    }

    @Test
    void fileNotFound_returns404() {
        when(chatFileService.getById(FILE_ID)).thenReturn(null);

        controller.downloadFile(FILE_ID, req, resp);

        assertEquals(HttpServletResponse.SC_NOT_FOUND, resp.getStatus());
        verifyNoInteractions(httpFileResponder);
        verify(conversationService, never()).isMember(any(), any());
    }

    @Test
    void fileDeleted_returns404() {
        when(chatFileService.getById(FILE_ID)).thenReturn(file("file", true));

        controller.downloadFile(FILE_ID, req, resp);

        assertEquals(HttpServletResponse.SC_NOT_FOUND, resp.getStatus());
        verifyNoInteractions(httpFileResponder);
    }

    @Test
    void notMember_returns403() {
        when(chatFileService.getById(FILE_ID)).thenReturn(file("file", false));
        when(conversationService.isMember(CONV_ID, ME)).thenReturn(false);

        controller.downloadFile(FILE_ID, req, resp);

        assertEquals(HttpServletResponse.SC_FORBIDDEN, resp.getStatus());
        verifyNoInteractions(httpFileResponder);
        verify(chatFileService, never()).download(any());
    }

    @Test
    void downloadNull_returns404() {
        when(chatFileService.getById(FILE_ID)).thenReturn(file("file", false));
        when(conversationService.isMember(CONV_ID, ME)).thenReturn(true);
        when(chatFileService.download(FILE_ID)).thenReturn(null);

        controller.downloadFile(FILE_ID, req, resp);

        assertEquals(HttpServletResponse.SC_NOT_FOUND, resp.getStatus());
        verifyNoInteractions(httpFileResponder);
    }

    @Test
    void netUrl_redirects302() {
        when(chatFileService.getById(FILE_ID)).thenReturn(file("file", false));
        when(conversationService.isMember(CONV_ID, ME)).thenReturn(true);
        ChatFileService.FileDownload d = new ChatFileService.FileDownload();
        d.netUrl = "https://cdn.example.com/x.png";
        when(chatFileService.download(FILE_ID)).thenReturn(d);

        controller.downloadFile(FILE_ID, req, resp);

        assertEquals(HttpServletResponse.SC_MOVED_TEMPORARILY, resp.getStatus());
        assertEquals("https://cdn.example.com/x.png", resp.getRedirectedUrl());
        verifyNoInteractions(httpFileResponder);
    }

    @Test
    void diskFile_image_writesInline() {
        File disk = new File("/tmp/a.png");
        when(chatFileService.getById(FILE_ID)).thenReturn(file("image", false));
        when(conversationService.isMember(CONV_ID, ME)).thenReturn(true);
        when(chatFileService.download(FILE_ID)).thenReturn(downloadDisk(disk, "a.png", "image/png"));

        controller.downloadFile(FILE_ID, req, resp);

        // image → attachment=false (inline)
        verify(httpFileResponder).write(eq(req), eq(resp), eq(disk), eq("a.png"), eq("image/png"), eq(false));
        verify(httpFileResponder, never()).writeBytes(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void diskFile_nonImage_writesAttachment() {
        File disk = new File("/tmp/doc.pdf");
        when(chatFileService.getById(FILE_ID)).thenReturn(file("file", false));
        when(conversationService.isMember(CONV_ID, ME)).thenReturn(true);
        when(chatFileService.download(FILE_ID)).thenReturn(downloadDisk(disk, "doc.pdf", "application/pdf"));

        controller.downloadFile(FILE_ID, req, resp);

        // 非 image → attachment=true
        verify(httpFileResponder).write(eq(req), eq(resp), eq(disk), eq("doc.pdf"), eq("application/pdf"), eq(true));
    }

    @Test
    void bytesFallback_image_writesInline() {
        byte[] bytes = new byte[]{1, 2, 3};
        when(chatFileService.getById(FILE_ID)).thenReturn(file("image", false));
        when(conversationService.isMember(CONV_ID, ME)).thenReturn(true);
        when(chatFileService.download(FILE_ID)).thenReturn(downloadBytes(bytes, "old.png", "image/png"));

        controller.downloadFile(FILE_ID, req, resp);

        // image → attachment=false (inline)
        verify(httpFileResponder).writeBytes(eq(resp), eq(bytes), eq("old.png"), eq("image/png"), eq(false));
        verify(httpFileResponder, never()).write(any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void bytesFallback_nonImage_writesAttachment() {
        byte[] bytes = new byte[]{9, 8, 7};
        when(chatFileService.getById(FILE_ID)).thenReturn(file("file", false));
        when(conversationService.isMember(CONV_ID, ME)).thenReturn(true);
        when(chatFileService.download(FILE_ID)).thenReturn(downloadBytes(bytes, "old.bin", "application/octet-stream"));

        controller.downloadFile(FILE_ID, req, resp);

        // 非 image → attachment=true
        verify(httpFileResponder).writeBytes(eq(resp), eq(bytes), eq("old.bin"), eq("application/octet-stream"), eq(true));
    }

    @Test
    void neitherDiskNorBytes_returns404() {
        when(chatFileService.getById(FILE_ID)).thenReturn(file("file", false));
        when(conversationService.isMember(CONV_ID, ME)).thenReturn(true);
        // diskFile==null && bytes==null && netUrl==null
        when(chatFileService.download(FILE_ID)).thenReturn(new ChatFileService.FileDownload());

        controller.downloadFile(FILE_ID, req, resp);

        assertEquals(HttpServletResponse.SC_NOT_FOUND, resp.getStatus());
        verifyNoInteractions(httpFileResponder);
    }
}
