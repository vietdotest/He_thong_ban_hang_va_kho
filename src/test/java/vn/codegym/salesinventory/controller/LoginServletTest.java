package vn.codegym.salesinventory.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.codegym.salesinventory.dto.AuthenticationResult;
import vn.codegym.salesinventory.security.CsrfTokenManager;
import vn.codegym.salesinventory.security.CurrentUser;
import vn.codegym.salesinventory.security.SessionKeys;
import vn.codegym.salesinventory.service.AuthenticationService;
import vn.codegym.salesinventory.service.SessionService;
import vn.codegym.salesinventory.validation.LoginValidator;

@ExtendWith(MockitoExtension.class)
class LoginServletTest {
    @Mock
    private AuthenticationService authenticationService;
    @Mock
    private CsrfTokenManager csrfTokenManager;
    @Mock
    private SessionService sessionService;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private HttpSession session;
    @Mock
    private RequestDispatcher dispatcher;

    private LoginServlet servlet;

    @BeforeEach
    void setUp() {
        servlet = new LoginServlet(authenticationService, sessionService, csrfTokenManager, new LoginValidator());
        when(request.getSession(false)).thenReturn(session);
    }

    @Test
    void rejectsInvalidCsrfBeforeAuthentication() throws Exception {
        when(request.getParameter("identity")).thenReturn("admin");
        when(request.getParameter("_csrf")).thenReturn("bad-token");
        when(csrfTokenManager.isValid(session, "bad-token")).thenReturn(false);
        when(request.getSession(true)).thenReturn(session);
        when(csrfTokenManager.getOrCreate(session)).thenReturn("new-token");
        when(request.getRequestDispatcher("/WEB-INF/views/auth/login.jsp")).thenReturn(dispatcher);

        servlet.doPost(request, response);

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        verify(authenticationService, never()).authenticate(any(), any());
        verify(dispatcher).forward(request, response);
    }

    @Test
    void successfulLoginRotatesSessionIdAndUsesSeeOther() throws Exception {
        CurrentUser currentUser = new CurrentUser(1L, "admin", "admin@local.test", "Admin");
        when(request.getParameter("identity")).thenReturn("admin");
        when(request.getParameter("password")).thenReturn("admin123");
        when(request.getParameter("_csrf")).thenReturn("valid-token");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(csrfTokenManager.isValid(session, "valid-token")).thenReturn(true);
        when(authenticationService.authenticate(any(), any())).thenReturn(AuthenticationResult.success(currentUser));
        when(request.changeSessionId()).thenReturn("rotated-session-id");
        when(sessionService.create(eq(currentUser), eq("rotated-session-id"), any())).thenReturn("server-session-id");
        when(request.getContextPath()).thenReturn("");
        when(response.encodeRedirectURL(any())).thenAnswer(invocation -> invocation.getArgument(0));

        servlet.doPost(request, response);

        verify(request).changeSessionId();
        verify(session).setAttribute(SessionKeys.CURRENT_USER, currentUser);
        verify(session).setAttribute(SessionKeys.SERVER_SESSION_ID, "server-session-id");
        verify(csrfTokenManager).rotate(session);
        verify(response).setStatus(HttpServletResponse.SC_SEE_OTHER);
        verify(response).setHeader("Location", "/dashboard");
        verify(request, never()).setAttribute(eq("password"), any());
    }

    @Test
    void authenticationFailureRendersOnlyGenericError() throws Exception {
        when(request.getParameter("identity")).thenReturn("admin");
        when(request.getParameter("password")).thenReturn("wrong");
        when(request.getParameter("_csrf")).thenReturn("valid-token");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(csrfTokenManager.isValid(session, "valid-token")).thenReturn(true);
        when(authenticationService.authenticate(any(), any())).thenReturn(AuthenticationResult.failure());
        when(request.getSession(true)).thenReturn(session);
        when(csrfTokenManager.getOrCreate(session)).thenReturn("valid-token");
        when(request.getRequestDispatcher("/WEB-INF/views/auth/login.jsp")).thenReturn(dispatcher);

        servlet.doPost(request, response);

        verify(request).setAttribute("formError", LoginServlet.GENERIC_AUTHENTICATION_ERROR);
        verify(request, never()).setAttribute(eq("password"), any());
        verify(dispatcher).forward(request, response);
    }
}
