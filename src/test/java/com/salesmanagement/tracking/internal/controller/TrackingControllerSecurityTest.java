package com.salesmanagement.tracking.internal.controller;

import com.salesmanagement.shared.security.UserPrincipal;
import com.salesmanagement.tracking.internal.service.LiveLocationBroadcaster;
import com.salesmanagement.tracking.internal.service.TrackingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Authorization tests for {@link TrackingController}.
 *
 * <p>Only the trail endpoint is exercised, because it is the only one whose rule is more than a
 * role check: {@code hasAnyRole('ADMIN','SALES_MANAGER') or #repId == principal.userId}. The other
 * three endpoints carry plain role expressions that Spring Security evaluates the same way
 * everywhere in this codebase; testing them would be testing the framework.</p>
 *
 * <p>That clause is worth its own tests for two reasons. It is the module's entire answer to the
 * privacy question this feature raises, and it depends on a SpEL property name that fails
 * <em>open-endedly wrong</em> when mistyped: {@code principal.id} does not throw, it simply never
 * matches, and every rep is denied access to their own data. A green test is the only thing that
 * distinguishes the two spellings.</p>
 *
 * <p>The principal is a Mockito mock rather than a constructed {@code UserPrincipal}, so these tests
 * stay compiling if that class gains or reorders constructor parameters. Method security reads
 * {@code getUserId()} and the authorities off the token; nothing else about the principal is
 * consulted.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TrackingControllerSecurityTest {

    private static final Long REP_A = 7L;
    private static final Long REP_B = 8L;

    @Autowired MockMvc mockMvc;

    @MockitoBean TrackingService trackingService;
    @MockitoBean LiveLocationBroadcaster broadcaster;

    @Test
    @DisplayName("11. a rep requesting another rep's trail is refused")
    void trail_forbidden_whenRepRequestsAnotherRepsTrail() throws Exception {
        mockMvc.perform(get("/api/tracking/reps/{repId}/trail", REP_B)
                        .param("date", "2026-07-22")
                        .with(authentication(repAuthentication(REP_A))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("12. a rep requesting their own trail is allowed")
    void trail_allowed_whenRepRequestsOwnTrail() throws Exception {
        given(trackingService.getTrail(anyLong(), any())).willReturn(List.of());

        mockMvc.perform(get("/api/tracking/reps/{repId}/trail", REP_A)
                        .param("date", "2026-07-22")
                        .with(authentication(repAuthentication(REP_A))))
                .andExpect(status().isOk());
    }

    /** An authenticated SALES_REP whose {@code getUserId()} returns the given id. */
    private static Authentication repAuthentication(Long userId) {
        UserPrincipal principal = mock(UserPrincipal.class);
        given(principal.getUserId()).willReturn(userId);

        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_SALES_REP")));
    }
}
