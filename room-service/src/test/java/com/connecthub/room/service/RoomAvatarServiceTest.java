package com.connecthub.room.service;

import com.connecthub.room.exception.BadRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RoomAvatarServiceTest {

    @TempDir
    Path tempDir;

    private final RoomAvatarService service = new RoomAvatarService();

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void saveAvatar_writesFileAndReturnsPublicUrl() throws Exception {
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/rooms/r1/avatar");
        request.setScheme("https");
        request.setServerName("connecthub.test");
        request.setServerPort(443);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "png-data".getBytes());

        String url = service.saveAvatar(file, "room-1");

        assertThat(url).startsWith("https://connecthub.test/api/v1/rooms/avatars/room-room-1-");
        assertThat(url).endsWith(".png");
        assertThat(Files.list(tempDir)).hasSize(1);
    }

    @Test
    void saveAvatar_rejectsEmptyFile() {
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[0]);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.saveAvatar(file, "room-1"));

        assertThat(ex.getMessage()).isEqualTo("Please choose an image to upload");
    }

    @Test
    void saveAvatar_rejectsOversizedFile() {
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[5 * 1024 * 1024 + 1]);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.saveAvatar(file, "room-1"));

        assertThat(ex.getMessage()).isEqualTo("Group photo must be 5MB or smaller");
    }

    @Test
    void saveAvatar_rejectsUnsupportedContentType() {
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
        MockMultipartFile file = new MockMultipartFile("file", "avatar.txt", "text/plain", "hello".getBytes());

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.saveAvatar(file, "room-1"));

        assertThat(ex.getMessage()).isEqualTo("Only JPG, PNG, GIF, and WebP images are supported");
    }

    @Test
    void loadAvatar_returnsResourceForExistingFile() throws Exception {
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
        Path avatar = tempDir.resolve("avatar.png");
        Files.writeString(avatar, "png-data");

        RoomAvatarService.AvatarResource resource = service.loadAvatar("avatar.png");

        assertThat(resource.resource().exists()).isTrue();
        assertThat(resource.mediaType()).isNotNull();
    }

    @Test
    void loadAvatar_rejectsMissingFile() {
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.loadAvatar("missing.png"));

        assertThat(ex.getMessage()).isEqualTo("Room photo not found");
    }
}
