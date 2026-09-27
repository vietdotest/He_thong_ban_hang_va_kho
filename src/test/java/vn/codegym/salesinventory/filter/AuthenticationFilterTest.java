package vn.codegym.salesinventory.filter;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.codegym.salesinventory.security.CurrentUser;
import vn.codegym.salesinventory.security.SessionKeys;
import vn.codegym.salesinventory.dto.SessionValidationResult;
import vn.codegym.salesinventory.service.SessionService;

@ExtendWith(MockitoExtension.class)
class AuthenticationFilterTest {
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private HttpSession session;
    @Mock
    private FilterChain chain;
    @Mock
    private SessionService sessionService;

    private AuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AuthenticationFilter(sessionService);
    }

    @Test
    void redirectsUnauthenticatedRequest() throws Exception {
        when(request.getSession(false)).thenReturn(null);
        when(request.getContextPath()).thenReturn("");
        when(response.encodeRedirectURL("/login")).thenReturn("/login");

        filter.doFilter(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_SEE_OTHER);
        verify(response).setHeader("Location", "/login");
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void authenticatedRequestContinuesWithNoStoreHeaders() throws Exception {
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(SessionKeys.CURRENT_USER))
                .thenReturn(new CurrentUser(1L, "admin", "admin@local.test", "Admin"));
        when(session.getId()).thenReturn("http-session-id");
        CurrentUser refreshedUser = new CurrentUser(1L, "admin", "admin@local.test", "Admin");
        when(sessionService.validate("http-session-id"))
                .thenReturn(SessionValidationResult.valid(refreshedUser, "server-session-id"));

        filter.doFilter(request, response, chain);

        verify(response).setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        verify(session).setAttribute(SessionKeys.CURRENT_USER, refreshedUser);
        verify(session).setAttribute(SessionKeys.SERVER_SESSION_ID, "server-session-id");
        verify(chain).doFilter(request, response);
    }

    @Test
    void revokedServerSessionIsRejected() throws Exception {
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(SessionKeys.CURRENT_USER))
                .thenReturn(new CurrentUser(1L, "admin", "admin@local.test", "Admin"));
        when(session.getId()).thenReturn("revoked-session");
        when(sessionService.validate("revoked-session")).thenReturn(SessionValidationResult.invalid());
        when(request.getContextPath()).thenReturn("");
        when(response.encodeRedirectURL("/login?reason=session_expired"))
                .thenReturn("/login?reason=session_expired");

        filter.doFilter(request, response, chain);

        verify(session).invalidate();
        verify(response).setHeader("Location", "/login?reason=session_expired");
        verify(chain, never()).doFilter(request, response);
    }
}
