package com.connecthub.auth.service;

import com.connecthub.auth.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProfileAvatarService {

    private static final long MAX_AVATAR_SIZE = 5L * 1024L * 1024L;
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/gif", ".gif",
            "image/webp", ".webp"
    );

    @Value("${connecthub.avatar.upload-dir:uploads/avatars}")
    private String uploadDir;

    public String saveAvatar(MultipartFile file, int userId) throws IOException {
        validate(file);

        Path dir = uploadRoot();
        Files.createDirectories(dir);

        String contentType = file.getContentType();
        String filename = "user-" + userId + "-" + UUID.randomUUID() + EXTENSIONS.get(contentType);
        Path target = dir.resolve(filename).normalize();
        if (!target.startsWith(dir)) {
            throw new BadRequestException("Invalid avatar filename");
        }

        file.transferTo(target);

        return ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/auth/avatars/")
                .path(filename)
                .toUriString();
    }

    public AvatarResource loadAvatar(String filename) throws MalformedURLException {
        Path dir = uploadRoot();
        Path file = dir.resolve(filename).normalize();
        if (!file.startsWith(dir) || !Files.exists(file) || !Files.isRegularFile(file)) {
            throw new BadRequestException("Avatar not found");
        }

        Resource resource = new UrlResource(file.toUri());
        String contentType;
        try {
            contentType = Files.probeContentType(file);
        } catch (IOException e) {
            contentType = null;
        }
        MediaType mediaType = contentType != null ? MediaType.parseMediaType(contentType) : MediaType.APPLICATION_OCTET_STREAM;
        return new AvatarResource(resource, mediaType);
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Please choose an image to upload");
        }
        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw new BadRequestException("Profile photo must be 5MB or smaller");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new BadRequestException("Only JPG, PNG, GIF, and WebP images are supported");
        }
    }

    private Path uploadRoot() {
        return Path.of(uploadDir).toAbsolutePath().normalize();
    }

    public record AvatarResource(Resource resource, MediaType mediaType) {}
}
