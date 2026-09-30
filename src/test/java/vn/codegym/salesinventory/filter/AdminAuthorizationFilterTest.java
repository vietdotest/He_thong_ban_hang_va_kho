package vn.codegym.salesinventory.filter;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.codegym.salesinventory.security.CurrentUser;
import vn.codegym.salesinventory.security.SessionKeys;

@ExtendWith(MockitoExtension.class)
class AdminAuthorizationFilterTest {
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private HttpSession session;
    @Mock private FilterChain chain;

    private final AdminAuthorizationFilter filter = new AdminAuthorizationFilter();

    @Test
    void allowsAdministrator() throws Exception {
        CurrentUser admin = new CurrentUser(1L, "admin", "admin@example.com", "Admin", Set.of("ADMIN"), false);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(SessionKeys.CURRENT_USER)).thenReturn(admin);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void rejectsNonAdministrator() throws Exception {
        CurrentUser sales = new CurrentUser(2L, "sales", "sales@example.com", "Sales", Set.of("SALES"), false);
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(SessionKeys.CURRENT_USER)).thenReturn(sales);

        filter.doFilter(request, response, chain);

        verify(response).sendError(HttpServletResponse.SC_FORBIDDEN,
                "Bạn không có quyền truy cập chức năng quản trị người dùng.");
        verify(chain, never()).doFilter(request, response);
    }
}
