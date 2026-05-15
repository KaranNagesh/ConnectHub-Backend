package com.connecthub.media.listener;

import com.connecthub.media.entity.MediaFile;
import com.connecthub.media.repository.MediaRepository;
import com.connecthub.media.service.MediaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserDeletionListener {

    private final MediaRepository mediaRepository;
    private final MediaService mediaService;

    @KafkaListener(topics = "auth.user.deleted", groupId = "media-service-group")
    @Transactional
    public void onUserDeleted(String userIdStr) {
        try {
            int userId = Integer.parseInt(userIdStr);
            log.warn("USER_DELETED event received for userId: {}. Triggering physical file deletion.", userId);

            List<MediaFile> userFiles = mediaRepository.findByUploaderId(userId);
            for (MediaFile file : userFiles) {
                // MediaService.delete handles both S3 object deletion and DB deletion
                mediaService.delete(file.getMediaId());
            }

            log.info("Successfully wiped {} media files from S3 and DB for user {}.", userFiles.size(), userId);
        } catch (NumberFormatException e) {
            log.error("Invalid userId format in USER_DELETED event: {}", userIdStr);
        } catch (Exception e) {
            log.error("Failed to delete media for user {}: {}", userIdStr, e.getMessage());
            throw e; // Retry later
        }
    }
}
