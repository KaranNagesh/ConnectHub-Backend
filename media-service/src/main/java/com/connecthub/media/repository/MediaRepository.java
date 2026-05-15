package com.connecthub.media.repository;
import com.connecthub.media.entity.MediaFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
public interface MediaRepository extends JpaRepository<MediaFile, String> {
    List<MediaFile> findByRoomIdOrderByUploadedAtDesc(String roomId);
    List<MediaFile> findByUploaderId(int uploaderId);
    int countByRoomId(String roomId);

    @Query("SELECT COALESCE(SUM(m.sizeKb), 0) FROM MediaFile m WHERE m.uploaderId = :uid")
    long sumSizeKbByUploaderId(@Param("uid") int uid);

    @Query("SELECT COALESCE(SUM(m.sizeKb), 0) FROM MediaFile m")
    long sumSizeKb();
}
