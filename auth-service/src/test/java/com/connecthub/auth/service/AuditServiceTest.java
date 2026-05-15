package com.connecthub.auth.service;

import com.connecthub.auth.entity.AuditLog;
import com.connecthub.auth.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditLogRepository auditRepo;

    @InjectMocks
    private AuditService auditService;

    @Test
    void log_savesAuditLogCorrectly() {
        auditService.log(1, "SUSPEND_USER", "USER", "99", "Suspended user 99", "192.168.1.1");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditRepo).save(captor.capture());

        AuditLog saved = captor.getValue();
        assertEquals(1, saved.getActorId());
        assertEquals("SUSPEND_USER", saved.getAction());
        assertEquals("USER", saved.getEntityType());
        assertEquals("99", saved.getEntityId());
        assertEquals("Suspended user 99", saved.getDetails());
        assertEquals("192.168.1.1", saved.getIpAddress());
    }

    @Test
    void getLogs_returnsPaginatedResults() {
        Page<AuditLog> page = new PageImpl<>(List.of(AuditLog.builder().actorId(1).build()));
        when(auditRepo.findAllByOrderByCreatedAtDesc(any(PageRequest.class))).thenReturn(page);

        Page<AuditLog> result = auditService.getLogs(0, 10);

        assertEquals(1, result.getTotalElements());
        assertEquals(1, result.getContent().get(0).getActorId());
        verify(auditRepo).findAllByOrderByCreatedAtDesc(PageRequest.of(0, 10));
    }
}
