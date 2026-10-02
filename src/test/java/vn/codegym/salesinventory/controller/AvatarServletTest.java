package vn.codegym.salesinventory.controller;

import java.io.ByteArrayInputStream;
import java.util.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.security.*;
import vn.codegym.salesinventory.service.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;

@ExtendWith(MockitoExtension.class)
class AvatarServletTest {
    @Mock AvatarService service;@Mock ProfileService profile;@Mock HttpServletRequest request;@Mock HttpServletResponse response;
    @Mock HttpSession session;@Mock ServletConfig config;@Mock ServletContext context;@Mock CsrfTokenManager csrf;@Mock Part image;@Mock RequestDispatcher dispatcher;
    AvatarServlet servlet;
    @BeforeEach void setup() throws Exception { servlet=new AvatarServlet(service,profile);servlet.init(config); }
    void post(boolean valid) {
        when(request.getSession(false)).thenReturn(session);when(config.getServletContext()).thenReturn(context);
        when(context.getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER)).thenReturn(csrf);when(request.getParameter("_csrf")).thenReturn("csrf");
        when(csrf.isValid(session,"csrf")).thenReturn(valid);
    }
    void owner() {
        when(session.getAttribute(SessionKeys.CURRENT_USER)).thenReturn(new CurrentUser(12,"self","self@test.local","Người thử"));
        when(request.getAttribute("access")).thenReturn(new Access(Set.of("SALES"),Set.of("PROFILE"),List.of(),List.of(),List.of()));
    }
    @Test void invalidCsrfDoesNotReadUpload() throws Exception {post(false);servlet.doPost(request,response);verify(response).sendError(403);verifyNoInteractions(service,image);}
    @Test void sessionOwnerWinsOverForgedTargetAndFilename() throws Exception {
        post(true);owner();when(request.getPart("image")).thenReturn(image);when(image.getSize()).thenReturn(3L);when(image.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3}));when(request.getContextPath()).thenReturn("");
        servlet.doPost(request,response);verify(service).replace(eq(12L),aryEq(new byte[]{1,2,3}));verify(response).setStatus(303);verify(request,never()).getParameter("userId");verify(image,never()).getSubmittedFileName();
    }
    @Test void invalidImageShowsEscapedFieldErrorOnProfile() throws Exception {
        post(true);owner();when(request.getPart("image")).thenReturn(image);when(image.getSize()).thenReturn((long)ImageStorage.MAX_BYTES+1);
        when(profile.find(12)).thenReturn(Map.of("full_name","Người thử","phone","0901234567"));when(request.getRequestDispatcher("/WEB-INF/views/account/profile.jsp")).thenReturn(dispatcher);
        servlet.doPost(request,response);verify(response).setStatus(400);verify(request).setAttribute(eq("errors"),argThat(v->((Map<?,?>)v).containsKey("image")));verifyNoInteractions(service);
    }
    @Test void missingPermissionIsForbiddenBeforeReadingFile() throws Exception {
        post(true);when(request.getAttribute("access")).thenReturn(new Access(Set.of(),Set.of(),List.of(),List.of(),List.of()));
        servlet.doPost(request,response);verify(response).sendError(403);verify(request,never()).getPart("image");
    }
    @Test void databaseFailureRemainsSystemError() throws Exception {
        post(true);owner();when(request.getPart("image")).thenReturn(image);when(image.getSize()).thenReturn(1L);when(image.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1}));doThrow(new IllegalStateException("database down")).when(service).replace(anyLong(),any());
        servlet.doPost(request,response);verify(response).sendError(500);verifyNoInteractions(profile);
    }
}
