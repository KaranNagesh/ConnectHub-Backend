package com.connecthub.admin.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests verify that the admin security config:
 * - Returns 401 for unauthenticated requests to protected pages (httpBasic takes precedence)
 * - Returns 200 or redirect for authenticated admin users
 * - Permits /actuator/health without auth
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminSecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticated_accessToRoot_shouldReturn401() throws Exception {
        mockMvc.perform(get("/"))
               .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticated_accessToApplications_shouldReturn401() throws Exception {
        mockMvc.perform(get("/applications"))
               .andExpect(status().isUnauthorized());
    }

    @Test
    void unauthenticated_accessToActuatorHealth_shouldBePermitted() throws Exception {
        mockMvc.perform(get("/actuator/health"))
               .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void authenticated_accessToRoot_shouldBeOk() throws Exception {
        mockMvc.perform(get("/"))
               .andExpect(status().isOk());
    }

    @Test
    void unauthenticated_loginPage_shouldBePermitted() throws Exception {
        mockMvc.perform(get("/login"))
               .andExpect(status().isOk());
    }
}
