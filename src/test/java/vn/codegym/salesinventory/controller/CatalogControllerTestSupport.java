package vn.codegym.salesinventory.controller;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.BeforeEach;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.security.*;
import static org.mockito.Mockito.*;

abstract class CatalogControllerTestSupport {
    HttpServletRequest request;HttpServletResponse response;HttpSession session;ServletConfig config;ServletContext context;CsrfTokenManager csrf;RequestDispatcher dispatcher;
    Map<String,String> params;Map<String,Object> attributes;
    @BeforeEach void prepareRequest() {
        request=mock(HttpServletRequest.class);response=mock(HttpServletResponse.class);session=mock(HttpSession.class);config=mock(ServletConfig.class);context=mock(ServletContext.class);csrf=mock(CsrfTokenManager.class);dispatcher=mock(RequestDispatcher.class);
        params=new HashMap<>();params.put("_csrf","valid");attributes=new HashMap<>();
        attributes.put("access",new Access(Set.of("SALES_MANAGER","WAREHOUSE"),Set.of("PRODUCT_MANAGE","CATALOG_READ","WAREHOUSE_MANAGE","PRICE_MANAGE","PRICE_READ"),List.of(),List.of(Map.of("id",1L,"name","Kho thử")),List.of()));
        when(request.getParameter(anyString())).thenAnswer(call->params.get(call.getArgument(0)));
        when(request.getAttribute(anyString())).thenAnswer(call->attributes.get(call.getArgument(0)));
        doAnswer(call->{attributes.put(call.getArgument(0),call.getArgument(1));return null;}).when(request).setAttribute(anyString(),any());
        when(request.getSession(false)).thenReturn(session);when(session.getAttribute(SessionKeys.CURRENT_USER)).thenReturn(new CurrentUser(12,"self","self@test.local","Người thử"));
        when(config.getServletContext()).thenReturn(context);when(context.getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER)).thenReturn(csrf);when(csrf.isValid(session,"valid")).thenReturn(true);
        when(request.getRequestDispatcher(anyString())).thenReturn(dispatcher);when(request.getContextPath()).thenReturn("");
    }
    void init(PortalServlet servlet) throws Exception { servlet.init(config); }
    void noPermissions() {attributes.put("access",new Access(Set.of(),Set.of(),List.of(),List.of(),List.of()));}
}
