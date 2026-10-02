package vn.codegym.salesinventory.controller;

import java.util.Map;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.security.*;
import vn.codegym.salesinventory.service.ProfileService;
import vn.codegym.salesinventory.validation.ProfileValidationException;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProfileServletTest {
    @Mock ProfileService service;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock HttpSession session;
    @Mock ServletConfig config;
    @Mock ServletContext context;
    @Mock CsrfTokenManager csrf;
    @Mock RequestDispatcher dispatcher;
    ProfileServlet servlet;
    final Map<String,Object> profile=Map.of("username","self","email","self@test.local","full_name","Tên hiện tại","phone","0901234567");

    @BeforeEach void prepare() throws Exception {
        servlet=new ProfileServlet(service);
        servlet.init(config);
    }
    void actor() {
        when(request.getSession(false)).thenReturn(session);
        when(session.getAttribute(SessionKeys.CURRENT_USER)).thenReturn(new CurrentUser(12,"self","self@test.local","Tên hiện tại"));
    }
    void validPost() {
        actor();
        when(config.getServletContext()).thenReturn(context);
        when(context.getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER)).thenReturn(csrf);
        when(request.getParameter("_csrf")).thenReturn("token");
        when(csrf.isValid(session,"token")).thenReturn(true);
    }
    void formView() {
        when(request.getRequestDispatcher("/WEB-INF/views/account/profile.jsp")).thenReturn(dispatcher);
        when(service.find(12)).thenReturn(profile);
    }

    @Test void getsOnlySessionOwnersProfile() throws Exception {
        actor();formView();servlet.doGet(request,response);
        verify(service).find(12);
        verify(request).setAttribute("form",Map.of("fullName","Tên hiện tại","phone","0901234567"));
        verify(request,never()).getParameter("userId");
        verify(dispatcher).forward(request,response);
    }
    @Test void updatesOnlySessionOwnerAndRedirects303() throws Exception {
        validPost();when(request.getParameter("fullName")).thenReturn(" Tên mới ");
        when(request.getParameter("phone")).thenReturn("+84 901234567");when(request.getContextPath()).thenReturn("/shop");
        servlet.doPost(request,response);
        verify(service).update(12," Tên mới ","+84 901234567");
        for(String key:new String[]{"userId","id","username","email","role","warehouseId","territoryId"}) verify(request,never()).getParameter(key);
        verify(response).setStatus(303);verify(response).setHeader("Location","/shop/account/profile?notice=saved");
    }
    @Test void preservesRawValuesAndFieldErrorsOn400() throws Exception {
        validPost();formView();when(request.getParameter("fullName")).thenReturn("  <script>bad</script>  ");
        when(request.getParameter("phone")).thenReturn("not-a-phone");
        var errors=Map.of("phone","Số điện thoại Việt Nam không đúng định dạng.");
        doThrow(new ProfileValidationException(errors)).when(service).update(12,"  <script>bad</script>  ","not-a-phone");
        servlet.doPost(request,response);
        verify(response).setStatus(400);verify(request).setAttribute("errors",errors);
        verify(request).setAttribute("form",Map.of("fullName","  <script>bad</script>  ","phone","not-a-phone"));
        verify(dispatcher).forward(request,response);verify(response,never()).setHeader(eq("Location"),anyString());
    }
    @Test void handlesMissingValuesWithoutNullFormEntries() throws Exception {
        validPost();formView();doThrow(new ProfileValidationException(Map.of("fullName","Thiếu tên","phone","Thiếu số"))).when(service).update(12,null,null);
        servlet.doPost(request,response);verify(request).setAttribute("form",Map.of("fullName","","phone",""));
    }
    @Test void rejectsInvalidCsrfBeforeCallingService() throws Exception {
        when(config.getServletContext()).thenReturn(context);
        when(request.getSession(false)).thenReturn(session);
        when(context.getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER)).thenReturn(csrf);
        when(request.getParameter("_csrf")).thenReturn("bad");
        servlet.doPost(request,response);verify(response).sendError(403);verifyNoInteractions(service);
    }
    @Test void rejectsMissingCsrfBeforeCallingService() throws Exception {
        when(config.getServletContext()).thenReturn(context);
        when(request.getSession(false)).thenReturn(session);
        when(context.getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER)).thenReturn(csrf);
        servlet.doPost(request,response);verify(response).sendError(403);verifyNoInteractions(service);
    }
    @Test void missingPermissionRemains403() throws Exception {
        validPost();doThrow(new SecurityException()).when(service).update(12,null,null);
        servlet.doPost(request,response);verify(response).sendError(403);
    }
    @Test void unexpectedDatabaseFailureRemains500() throws Exception {
        validPost();doThrow(new IllegalStateException("DB unavailable")).when(service).update(12,null,null);
        servlet.doPost(request,response);verify(response).sendError(500);verify(context).log(eq("Không thể xử lý yêu cầu"),any(Throwable.class));
    }
}
