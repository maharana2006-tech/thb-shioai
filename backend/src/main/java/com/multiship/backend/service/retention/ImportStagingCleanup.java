package com.multiship.backend.service.retention;

import com.multiship.backend.repository.ImportStagingUploadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Deletes staged bulk uploads (and their rows) once they pass the retention window. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportStagingCleanup {

    private final ImportStagingUploadRepository uploads;

    @Value("${import.staging.retention-days:7}")
    private int retentionDays;

    @Scheduled(cron = "0 45 3 * * *")
    public void purgeExpiredUploads() {
        int days = Math.max(1, retentionDays);
        int n = uploads.deleteOlderThan(LocalDateTime.now().minusDays(days));
        if (n > 0) log.info("Import staging: purged {} upload(s) older than {} day(s)", n, days);
    }
}
