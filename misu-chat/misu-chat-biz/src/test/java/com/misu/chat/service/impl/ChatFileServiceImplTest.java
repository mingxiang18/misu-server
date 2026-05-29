package com.misu.chat.service.impl;

import com.alibaba.fastjson2.JSON;
import com.bb.bot.entity.bb.BbMessageContent;
import com.misu.account.dto.UserBriefDto;
import com.misu.chat.domain.dto.FileDto;
import com.misu.chat.domain.entity.ChatFile;
import com.misu.chat.domain.entity.ChatMessage;
import com.misu.chat.repository.ChatFileRepository;
import com.misu.chat.repository.ChatMessageRepository;
import com.misu.chat.service.ChatFileService;
import com.misu.chat.service.UserInfoService;
import com.misu.common.exception.ServiceException;
import com.misu.framework.upload.ChunkedUploadAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatFileServiceImplTest {

    @Mock
    private ChatFileRepository fileRepository;
    @Mock
    private ChatMessageRepository messageRepository;
    @Mock
    private UserInfoService userInfoService;

    @InjectMocks
    private ChatFileServiceImpl service;

    @TempDir
    Path tmp;

    private final AtomicLong idSeq = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        // 真实组件跑真实合并
        ReflectionTestUtils.setField(service, "chunkedUploadAssembler", new ChunkedUploadAssembler());
        ReflectionTestUtils.setField(service, "configuredPath", tmp.toString());
        // save 回填自增 id
        when(fileRepository.save(any(ChatFile.class))).thenAnswer(inv -> {
            ChatFile f = inv.getArgument(0);
            if (f.getId() == null) {
                f.setId(idSeq.getAndIncrement());
            }
            return f;
        });
    }

    // ---------- 1. saveUploaded ----------

    @Test
    void saveUploaded_disk_setsFieldsAndStoresFile() throws Exception {
        byte[] body = "hello world".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", body);

        FileDto dto = service.saveUploaded(100L, "u1", "USER", file, "file");

        assertNotNull(dto.getId());
        assertEquals("doc.pdf", dto.getFileName());
        assertEquals("application/pdf", dto.getMimeType());
        assertEquals(body.length, dto.getSize());
        assertEquals("file", dto.getCategory());
        assertEquals("USER", dto.getSenderType());
        assertEquals("u1", dto.getUploaderUserId());

        // 验证落库实体
        ChatFile saved = captureSavedFile();
        assertEquals(100L, saved.getConversationId());
        assertEquals("file", saved.getCategory());
        assertTrue(saved.getStorePath().startsWith("100/"));
        assertTrue(saved.getStorePath().endsWith(".pdf"));
        assertFalse(saved.getDeleted());

        // 文件真实落盘
        Path onDisk = tmp.resolve(saved.getStorePath());
        assertTrue(Files.exists(onDisk));
        assertArrayEquals(body, Files.readAllBytes(onDisk));
    }

    @Test
    void saveUploaded_imageCategoryNormalized() {
        MockMultipartFile file = new MockMultipartFile("file", "a.png", "image/png", "x".getBytes());
        FileDto dto = service.saveUploaded(1L, "u1", "USER", file, "image");
        assertEquals("image", dto.getCategory());
    }

    @Test
    void saveUploaded_unknownCategoryFallsBackToFile() {
        MockMultipartFile file = new MockMultipartFile("file", "a.bin", "application/octet-stream", "x".getBytes());
        FileDto dto = service.saveUploaded(1L, "u1", "USER", file, "weird");
        assertEquals("file", dto.getCategory());
    }

    @Test
    void saveUploaded_emptyFile_throwsBadRequest() {
        MockMultipartFile empty = new MockMultipartFile("file", "e.txt", "text/plain", new byte[0]);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.saveUploaded(1L, "u1", "USER", empty, "file"));
        assertEquals(400, ex.getCode());
    }

    @Test
    void saveUploaded_nullFile_throwsBadRequest() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.saveUploaded(1L, "u1", "USER", null, "file"));
        assertEquals(400, ex.getCode());
    }

    // ---------- 2. saveUploadedChunk ----------

    private MockMultipartFile chunk(byte[] b) {
        return new MockMultipartFile("chunk", "part", "application/octet-stream", b);
    }

    @Test
    void chunk_emptyChunk_throwsBadRequest() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.saveUploadedChunk(1L, "u1", "USER", "up1", chunk(new byte[0]),
                        0, 2, "f.bin", "application/octet-stream", null, "file"));
        assertEquals(400, ex.getCode());
    }

    @Test
    void chunk_blankUploadId_throwsBadRequest() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.saveUploadedChunk(1L, "u1", "USER", "  ", chunk("a".getBytes()),
                        0, 2, "f.bin", "application/octet-stream", null, "file"));
        assertEquals(400, ex.getCode());
    }

    @Test
    void chunk_totalChunksZero_throwsBadRequest() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.saveUploadedChunk(1L, "u1", "USER", "up1", chunk("a".getBytes()),
                        0, 0, "f.bin", "application/octet-stream", null, "file"));
        assertEquals(400, ex.getCode());
    }

    @Test
    void chunk_indexOutOfRange_throwsBadRequest() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.saveUploadedChunk(1L, "u1", "USER", "up1", chunk("a".getBytes()),
                        2, 2, "f.bin", "application/octet-stream", null, "file"));
        assertEquals(400, ex.getCode());
    }

    @Test
    void chunk_uploadIdSanitizedToEmpty_throwsBadRequest() {
        // 全是非法字符 → 清洗后为空
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.saveUploadedChunk(1L, "u1", "USER", "/../", chunk("a".getBytes()),
                        0, 2, "f.bin", "application/octet-stream", null, "file"));
        assertEquals(400, ex.getCode());
    }

    @Test
    void chunk_notAllPresent_returnsIncomplete() {
        ChatFileService.ChunkUploadResult r = service.saveUploadedChunk(
                7L, "u1", "USER", "up1", chunk("part0".getBytes()),
                0, 2, "f.bin", "application/octet-stream", null, "file");
        assertFalse(r.complete);
        assertNull(r.file);
        // 还没合并，不该落库
        verify(fileRepository, never()).save(any());
    }

    @Test
    void chunk_lastChunkArrives_mergesAndPersists() throws Exception {
        byte[] p0 = "AAAA".getBytes();
        byte[] p1 = "BBBB".getBytes();

        ChatFileService.ChunkUploadResult r0 = service.saveUploadedChunk(
                9L, "u1", "USER", "up-merge", chunk(p0), 0, 2, "big.bin", "application/octet-stream", null, "file");
        assertFalse(r0.complete);

        ChatFileService.ChunkUploadResult r1 = service.saveUploadedChunk(
                9L, "u1", "USER", "up-merge", chunk(p1), 1, 2, "big.bin", "application/octet-stream", null, "file");
        assertTrue(r1.complete);
        assertNotNull(r1.file);
        assertEquals("big.bin", r1.file.getFileName());
        assertEquals(8L, r1.file.getSize()); // 入参 fileSize=null → 用合并字节数

        ChatFile saved = captureSavedFile();
        Path onDisk = tmp.resolve(saved.getStorePath());
        assertTrue(Files.exists(onDisk));
        assertArrayEquals("AAAABBBB".getBytes(), Files.readAllBytes(onDisk));
        // chunkDir 被清理
        assertFalse(Files.exists(tmp.resolve("_chunks").resolve("up-merge")));
    }

    @Test
    void chunk_outOfOrderArrival_mergesInIndexOrder() throws Exception {
        // 先传 part1，再传 part0
        ChatFileService.ChunkUploadResult r1 = service.saveUploadedChunk(
                3L, "u1", "USER", "ooo", chunk("ONE".getBytes()), 1, 2, "x.bin", "application/octet-stream", null, "file");
        assertFalse(r1.complete);

        ChatFileService.ChunkUploadResult r0 = service.saveUploadedChunk(
                3L, "u1", "USER", "ooo", chunk("ZERO".getBytes()), 0, 2, "x.bin", "application/octet-stream", null, "file");
        assertTrue(r0.complete);

        ChatFile saved = captureSavedFile();
        Path onDisk = tmp.resolve(saved.getStorePath());
        // 按 index 顺序：part0=ZERO, part1=ONE
        assertArrayEquals("ZEROONE".getBytes(), Files.readAllBytes(onDisk));
    }

    @Test
    void chunk_explicitFileSizePreferredOverMerged() {
        service.saveUploadedChunk(5L, "u1", "USER", "fs", chunk("12".getBytes()), 0, 2, "y.bin", "m", null, "file");
        ChatFileService.ChunkUploadResult r = service.saveUploadedChunk(
                5L, "u1", "USER", "fs", chunk("34".getBytes()), 1, 2, "y.bin", "m", 999L, "file");
        assertTrue(r.complete);
        assertEquals(999L, r.file.getSize());
    }

    // ---------- 3. download ----------

    @Test
    void download_diskFile_returnsDiskFile() throws Exception {
        // 先真实落一个磁盘文件
        byte[] body = "disk-content".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "d.txt", "text/plain", body);
        FileDto dto = service.saveUploaded(11L, "u1", "USER", file, "file");
        ChatFile saved = captureSavedFile();
        saved.setId(dto.getId());

        when(fileRepository.findById(dto.getId())).thenReturn(Optional.of(saved));

        ChatFileService.FileDownload d = service.download(dto.getId());
        assertNotNull(d);
        assertNotNull(d.diskFile);
        assertNull(d.bytes);
        assertNull(d.netUrl);
        assertEquals("d.txt", d.fileName);
        assertEquals("text/plain", d.mimeType);
        assertTrue(d.diskFile.exists());
        assertArrayEquals(body, Files.readAllBytes(d.diskFile.toPath()));
    }

    @Test
    void download_diskFileMissing_returnsNull() {
        ChatFile f = new ChatFile();
        f.setId(50L);
        f.setFileName("gone.txt");
        f.setStorePath("11/nonexistent.txt");
        f.setDeleted(false);
        when(fileRepository.findById(50L)).thenReturn(Optional.of(f));

        assertNull(service.download(50L));
    }

    @Test
    void download_deleted_returnsNull() {
        ChatFile f = new ChatFile();
        f.setId(51L);
        f.setDeleted(true);
        f.setStorePath("11/x.txt");
        when(fileRepository.findById(51L)).thenReturn(Optional.of(f));
        assertNull(service.download(51L));
    }

    @Test
    void download_netUrl_returnsNetUrl() {
        ChatFile f = new ChatFile();
        f.setId(52L);
        f.setFileName("remote.zip");
        f.setMimeType("application/zip");
        f.setNetUrl("https://example.com/remote.zip");
        f.setDeleted(false);
        when(fileRepository.findById(52L)).thenReturn(Optional.of(f));

        ChatFileService.FileDownload d = service.download(52L);
        assertNotNull(d);
        assertEquals("https://example.com/remote.zip", d.netUrl);
        assertNull(d.diskFile);
        assertNull(d.bytes);
    }

    @Test
    void download_legacyBase64_decodesBytes() {
        byte[] raw = "legacy-bytes".getBytes();
        String b64 = Base64.getEncoder().encodeToString(raw);

        ChatFile f = new ChatFile();
        f.setId(53L);
        f.setFileName("legacy.bin");
        f.setMimeType("application/octet-stream");
        f.setMessageId(900L);
        f.setDeleted(false);
        // storePath / netUrl 均空 → 走 base64 兜底
        when(fileRepository.findById(53L)).thenReturn(Optional.of(f));

        ChatMessage msg = new ChatMessage();
        msg.setId(900L);
        List<Map<String, Object>> content = new ArrayList<>();
        Map<String, Object> item = new HashMap<>();
        item.put("type", "localFile");
        item.put("fileName", "legacy.bin");
        item.put("data", b64);
        content.add(item);
        msg.setContentJson(JSON.toJSONString(content));
        when(messageRepository.findById(900L)).thenReturn(Optional.of(msg));

        ChatFileService.FileDownload d = service.download(53L);
        assertNotNull(d);
        assertArrayEquals(raw, d.bytes);
        assertNull(d.diskFile);
    }

    @Test
    void download_notFound_returnsNull() {
        when(fileRepository.findById(404L)).thenReturn(Optional.empty());
        assertNull(service.download(404L));
    }

    // ---------- 4. listFiles ----------

    @Test
    void listFiles_filtersImages_sortsDesc_resolvesNamesAndCanDelete() {
        ChatFile img = file(1L, "image", "pic.png", "uOther", "USER");
        ChatFile doc1 = file(2L, "file", "a.pdf", "uMe", "USER");
        ChatFile doc2 = file(3L, "file", "b.pdf", "uOther", "USER");
        ChatFile botDoc = file(4L, "file", "bot.pdf", null, "BOT");

        // repository 已按 createTime desc 返回
        when(fileRepository.findByConversationIdAndDeletedFalseOrderByCreateTimeDesc(10L))
                .thenReturn(List.of(botDoc, doc2, doc1, img));

        UserBriefDto me = new UserBriefDto();
        me.setUserName("me_name");
        me.setNickName("MeNick");
        UserBriefDto other = new UserBriefDto();
        other.setUserName("other_name");
        // other 无 nickName → 回退 userName
        Map<String, UserBriefDto> userMap = new HashMap<>();
        userMap.put("uMe", me);
        userMap.put("uOther", other);
        when(userInfoService.batchGet(anyCollection())).thenReturn(userMap);

        // currentUser = uMe，群主 = ownerX
        List<FileDto> out = service.listFiles(10L, "uMe", "ownerX");

        // image 被过滤
        assertEquals(3, out.size());
        assertTrue(out.stream().noneMatch(d -> "image".equals(d.getCategory())));

        // 顺序保持 repository 返回（desc）：bot, doc2, doc1
        assertEquals(4L, out.get(0).getId());
        assertEquals(3L, out.get(1).getId());
        assertEquals(2L, out.get(2).getId());

        FileDto botDto = out.get(0);
        assertEquals("冥想bb", botDto.getUploaderName());

        FileDto doc2Dto = out.get(1); // uOther
        assertEquals("other_name", doc2Dto.getUploaderName()); // nickName 空 → userName
        assertFalse(doc2Dto.getCanDelete()); // 非上传者非群主

        FileDto doc1Dto = out.get(2); // uMe
        assertEquals("MeNick", doc1Dto.getUploaderName());
        assertTrue(doc1Dto.getCanDelete()); // 上传者本人
    }

    @Test
    void listFiles_owner_canDeleteAll() {
        ChatFile doc = file(2L, "file", "a.pdf", "uSomeone", "USER");
        when(fileRepository.findByConversationIdAndDeletedFalseOrderByCreateTimeDesc(10L))
                .thenReturn(List.of(doc));
        when(userInfoService.batchGet(anyCollection())).thenReturn(Collections.emptyMap());

        // currentUser == owner
        List<FileDto> out = service.listFiles(10L, "owner1", "owner1");
        assertTrue(out.get(0).getCanDelete());
        // 无 user 信息 → uploaderName 回退 uploaderUserId
        assertEquals("uSomeone", out.get(0).getUploaderName());
    }

    // ---------- 5. delete ----------

    @Test
    void delete_byUploader_softDeletes() {
        ChatFile f = file(60L, "file", "x.pdf", "u1", "USER");
        when(fileRepository.findById(60L)).thenReturn(Optional.of(f));
        assertTrue(service.delete(60L, "u1", "ownerX"));
        assertTrue(f.getDeleted());
        verify(fileRepository).save(f);
    }

    @Test
    void delete_byOwner_softDeletes() {
        ChatFile f = file(61L, "file", "x.pdf", "u1", "USER");
        when(fileRepository.findById(61L)).thenReturn(Optional.of(f));
        assertTrue(service.delete(61L, "owner1", "owner1"));
        assertTrue(f.getDeleted());
    }

    @Test
    void delete_byOther_denied() {
        ChatFile f = file(62L, "file", "x.pdf", "u1", "USER");
        when(fileRepository.findById(62L)).thenReturn(Optional.of(f));
        assertFalse(service.delete(62L, "stranger", "owner1"));
        verify(fileRepository, never()).save(any());
    }

    @Test
    void delete_notFound_returnsFalse() {
        when(fileRepository.findById(63L)).thenReturn(Optional.empty());
        assertFalse(service.delete(63L, "u1", "owner1"));
    }

    // ---------- 6. indexFromMessage ----------

    @Test
    void indexFromMessage_onlyNetFileIndexed() {
        ChatMessage msg = new ChatMessage();
        msg.setId(700L);
        msg.setConversationId(20L);
        msg.setSenderUserId("uX");
        msg.setSenderType("USER");

        List<Map<String, Object>> content = new ArrayList<>();
        Map<String, Object> text = new HashMap<>();
        text.put("type", "text");
        text.put("data", "hi");
        Map<String, Object> net = new HashMap<>();
        net.put("type", "netFile");
        net.put("fileName", "report.pdf");
        net.put("mimeType", "application/pdf");
        net.put("size", 1234L);
        net.put("data", "https://cdn/report.pdf");
        content.add(text);
        content.add(net);
        msg.setContentJson(JSON.toJSONString(content));

        service.indexFromMessage(msg);

        ChatFile saved = captureSavedFile();
        verify(fileRepository, times(1)).save(any(ChatFile.class));
        assertEquals("netFile", saved.getSourceType());
        assertEquals("https://cdn/report.pdf", saved.getNetUrl());
        assertEquals("file", saved.getCategory());
        assertEquals("report.pdf", saved.getFileName());
        assertEquals(20L, saved.getConversationId());
        assertEquals(700L, saved.getMessageId());
    }

    @Test
    void indexFromMessage_invalidJson_safelyReturns() {
        ChatMessage msg = new ChatMessage();
        msg.setId(701L);
        msg.setContentJson("not-json{");
        service.indexFromMessage(msg);
        verify(fileRepository, never()).save(any());
    }

    @Test
    void indexFromMessage_nullContent_returns() {
        ChatMessage msg = new ChatMessage();
        msg.setId(702L);
        msg.setContentJson(null);
        service.indexFromMessage(msg);
        verify(fileRepository, never()).save(any());
    }

    // ---------- 7. referenceBotInlineAttachments ----------

    @Test
    void referenceBot_localImageAndFile_replacedWithReference() {
        byte[] imgRaw = "img-bytes".getBytes();
        byte[] fileRaw = "file-bytes".getBytes();
        String imgB64 = Base64.getEncoder().encodeToString(imgRaw);
        String fileB64 = "data:application/pdf;base64," + Base64.getEncoder().encodeToString(fileRaw);

        BbMessageContent img = BbMessageContent.builder()
                .type("localImage").data(imgB64).fileName("p.png").mimeType("image/png").size((long) imgRaw.length).build();
        BbMessageContent f = BbMessageContent.builder()
                .type("localFile").data(fileB64).fileName("d.pdf").mimeType("application/pdf").size((long) fileRaw.length).build();
        BbMessageContent text = BbMessageContent.builder().type("text").data("hi").build();

        List<BbMessageContent> out = service.referenceBotInlineAttachments(30L, List.of(img, f, text));

        assertEquals(3, out.size());
        // image → chatImage + fileId
        assertEquals("chatImage", out.get(0).getType());
        assertNotNull(out.get(0).getData());
        assertEquals("p.png", out.get(0).getFileName());
        // file → chatFile + fileId
        assertEquals("chatFile", out.get(1).getType());
        assertNotNull(out.get(1).getData());
        // text 原样
        assertEquals("text", out.get(2).getType());
        assertEquals("hi", out.get(2).getData());

        // 落库两个，senderType=BOT
        verify(fileRepository, times(2)).save(any(ChatFile.class));
    }

    @Test
    void referenceBot_saveFailure_keepsOriginal() {
        // data 非法 base64 → 解码抛异常 → 保留原内容项
        BbMessageContent img = BbMessageContent.builder()
                .type("localImage").data("@@@not-base64@@@").fileName("p.png").mimeType("image/png").build();

        List<BbMessageContent> out = service.referenceBotInlineAttachments(31L, List.of(img));
        assertEquals(1, out.size());
        assertEquals("localImage", out.get(0).getType());
        verify(fileRepository, never()).save(any());
    }

    @Test
    void referenceBot_nullContent_returnedAsIs() {
        assertNull(service.referenceBotInlineAttachments(1L, null));
        assertTrue(service.referenceBotInlineAttachments(1L, Collections.emptyList()).isEmpty());
    }

    // ---------- helpers ----------

    private ChatFile captureSavedFile() {
        var captor = org.mockito.ArgumentCaptor.forClass(ChatFile.class);
        verify(fileRepository, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private ChatFile file(Long id, String category, String name, String uploader, String senderType) {
        ChatFile f = new ChatFile();
        f.setId(id);
        f.setConversationId(10L);
        f.setCategory(category);
        f.setFileName(name);
        f.setUploaderUserId(uploader);
        f.setSenderType(senderType);
        f.setDeleted(false);
        f.setCreateTime(LocalDateTime.now());
        return f;
    }
}
