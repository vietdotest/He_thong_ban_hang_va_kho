package vn.codegym.salesinventory.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.security.*;
import vn.codegym.salesinventory.service.AccessService;
import static org.mockito.Mockito.*;

class AuthorizationFilterTest {
    @ParameterizedTest
    @CsvSource({"/accounting/returns,receive,STOCK_RETURN_RECEIVE", "/accounting/returns,credit,CREDIT_ISSUE", "/accounting/returns,save,RETURN_WRITE", "/accounting/returns,cancel,RETURN_WRITE", "/fulfillment/shipments,recover,STOCK_RETURN_RECEIVE"})
    void actualFilterUsesTheActionPermission(String path,String action,String permission)throws Exception {
        var request=mock(HttpServletRequest.class);var response=mock(HttpServletResponse.class);var chain=mock(FilterChain.class);
        var session=mock(HttpSession.class);var context=mock(ServletContext.class);var service=mock(AccessService.class);
        var filter=new AuthorizationFilter();var field=AuthorizationFilter.class.getDeclaredField("service");field.setAccessible(true);field.set(filter,service);
        when(request.getRequestURI()).thenReturn(path);when(request.getContextPath()).thenReturn("");when(request.getMethod()).thenReturn("POST");
        when(request.getParameter("action")).thenReturn(action);when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(SessionKeys.CURRENT_USER)).thenReturn(new CurrentUser(7,"tester","test@example.test","Người thử",Set.of(),false));
        when(request.getServletContext()).thenReturn(context);when(context.getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER)).thenReturn(new CsrfTokenManager());
        when(service.load(7)).thenReturn(new Access(Set.of("WAREHOUSE_MANAGER"),Set.of(permission),List.of(),List.of(),List.of()));
        filter.doFilter(request,response,chain);verify(chain).doFilter(request,response);verify(response,never()).sendError(anyInt());
        reset(chain,response);when(service.load(7)).thenReturn(new Access(Set.of("WAREHOUSE_MANAGER"),Set.of("PROFILE"),List.of(),List.of(),List.of()));
        filter.doFilter(request,response,chain);verify(response).sendError(403);verifyNoInteractions(chain);
    }
    @Test void duplicateActionIsRejectedBeforeAuthorization()throws Exception {
        var request=mock(HttpServletRequest.class);var response=mock(HttpServletResponse.class);var chain=mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/inventory/receipts");when(request.getContextPath()).thenReturn("");when(request.getMethod()).thenReturn("POST");
        when(request.getParameterValues("action")).thenReturn(new String[]{"new","save"});
        new AuthorizationFilter().doFilter(request,response,chain);verify(response).sendError(400);verifyNoInteractions(chain);
    }
}
