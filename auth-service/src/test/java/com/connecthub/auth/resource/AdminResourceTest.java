package com.connecthub.auth.resource;

import com.connecthub.auth.entity.AuditLog;
import com.connecthub.auth.entity.User;
import com.connecthub.auth.service.AuditService;
import com.connecthub.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminResourceTest {

    @Mock
    private AuthService authService;

    @Mock
    private AuditService auditService;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private AdminResource resource;

    @Test
    void suspend_suspendsUserAndWritesAuditLog() {
        User user = user(5, "blocked", true, "ACTIVE", "USER");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(authService.suspendUser(5)).thenReturn(user);

        ResponseEntity<User> response = resource.suspend(5, 99, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(user);
        verify(auditService).log(99, "USER_SUSPEND", "USER", "5", "Suspended: blocked", "127.0.0.1");
    }

    @Test
    void reactivate_reactivatesUserAndWritesAuditLog() {
        User user = user(5, "active", true, "ACTIVE", "USER");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(authService.reactivateUser(5)).thenReturn(user);

        ResponseEntity<User> response = resource.reactivate(5, 99, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(auditService).log(99, "USER_REACTIVATE", "USER", "5", "Reactivated: active", "127.0.0.1");
    }

    @Test
    void delete_deletesUserAndWritesAuditLog() {
        User user = user(5, "gone", true, "ACTIVE", "USER");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(authService.getUserById(5)).thenReturn(user);

        ResponseEntity<Void> response = resource.delete(5, 99, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(authService).deleteUser(5);
        verify(auditService).log(99, "USER_DELETE", "USER", "5", "Deleted: gone", "127.0.0.1");
    }

    @Test
    void getAuditLogs_returnsPageFromAuditService() {
        Page<AuditLog> page = new PageImpl<>(List.of(new AuditLog()));
        when(auditService.getLogs(1, 25)).thenReturn(page);

        ResponseEntity<Page<AuditLog>> response = resource.getAuditLogs(1, 25);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(page);
    }

    @Test
    void getAllUsers_returnsUsers() {
        List<User> users = List.of(user(1, "one", true, "ONLINE", "USER"));
        when(authService.getAllUsers()).thenReturn(users);

        ResponseEntity<List<User>> response = resource.getAllUsers();

        assertThat(response.getBody()).isEqualTo(users);
    }

    @Test
    void analytics_countsUserStates() {
        when(authService.getAllUsers()).thenReturn(List.of(
                user(1, "online", true, "ONLINE", "USER"),
                user(2, "suspended", true, "SUSPENDED", "USER"),
                user(3, "inactive", false, "OFFLINE", "USER"),
                user(4, "admin", true, "OFFLINE", "PLATFORM_ADMIN")
        ));

        ResponseEntity<Map<String, Object>> response = resource.analytics();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("totalUsers", 4);
        assertThat(response.getBody()).containsEntry("activeUsers", 2L);
        assertThat(response.getBody()).containsEntry("suspendedUsers", 2L);
        assertThat(response.getBody()).containsEntry("adminUsers", 1L);
        assertThat(response.getBody()).containsEntry("onlineUsers", 1L);
    }

    private User user(int id, String username, boolean active, String status, String role) {
        return User.builder()
                .userId(id)
                .username(username)
                .email(username + "@example.com")
                .active(active)
                .status(status)
                .role(role)
                .build();
    }
}
