package vn.codegym.salesinventory.controller;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.security.*;
import vn.codegym.salesinventory.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserImportServletTest {
    @Mock UserImportService service;
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock HttpSession session;
    @Mock ServletContext context;
    @Mock ServletConfig config;
    @Mock CsrfTokenManager csrf;
    @Mock RequestDispatcher dispatcher;
    @Mock Part file;
    UserImportServlet servlet;

    @BeforeEach void prepare() throws Exception {
        servlet = new UserImportServlet(service);
        lenient().when(config.getServletContext()).thenReturn(context); servlet.init(config);
        lenient().when(request.getSession()).thenReturn(session);
        lenient().when(request.getSession(false)).thenReturn(session);
        lenient().when(request.getAttribute("access")).thenReturn(new Access(Set.of("ADMIN"),Set.of("USER_MANAGE"),List.of(),List.of(),List.of()));
        lenient().when(session.getAttribute(SessionKeys.CURRENT_USER)).thenReturn(new CurrentUser(12,"actor","actor@test.local","Người nhập"));
        lenient().when(context.getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER)).thenReturn(csrf);
        lenient().when(request.getParameter("_csrf")).thenReturn("valid");
        lenient().when(csrf.isValid(session,"valid")).thenReturn(true);
        lenient().when(request.getRequestDispatcher("/WEB-INF/views/imports/import.jsp")).thenReturn(dispatcher);
    }

    private ImportPreview preview() { return new ImportPreview(12,List.of(new ImportPreview.Line(2,List.of("new-user"),"Tạo mới","")),Instant.now()); }
    private void confirm(ImportPreview value) {
        when(request.getParameter("action")).thenReturn("confirm");
        when(session.getAttribute("userImport")).thenReturn(value);
        lenient().when(request.getParameter("token")).thenReturn(value.getToken());
    }

    @Test void downloadsReadableTemplateWithExpectedColumns() throws Exception {
        var bytes = new ByteArrayOutputStream();
        var stream = new ServletOutputStream() { public boolean isReady(){return true;} public void setWriteListener(WriteListener l){} public void write(int b){bytes.write(b);} };
        lenient().when(request.getParameter("template")).thenReturn("1"); when(response.getOutputStream()).thenReturn(stream);
        servlet.doGet(request,response);
        assertThat(Xlsx.read(bytes.toByteArray()).get(0).cells()).isEqualTo(UserImportService.HEADERS);
        verify(response).setHeader("Content-Disposition","attachment; filename=nguoi-dung.xlsx"); verifyNoInteractions(service);
    }

    @Test void previewUsesSessionActorAndClosesFileStream() throws Exception {
        var value = preview(); var input = spy(new ByteArrayInputStream(new byte[]{1,2}));
        when(request.getPart("file")).thenReturn(file); when(file.getSize()).thenReturn(2L); when(file.getInputStream()).thenReturn(input);
        when(service.preview(eq(12L),any())).thenReturn(value);
        servlet.doPost(request,response);
        verify(service).preview(eq(12L),aryEq(new byte[]{1,2})); verify(input).close();
        verify(session).setAttribute("userImport",value); verify(request).setAttribute("validCount",1L); verify(dispatcher).forward(request,response);
    }

    @Test void missingFileReturnsFieldErrorAndClearsOldPreview() throws Exception {
        servlet.doPost(request,response);
        verify(response).setStatus(400); verify(request).setAttribute(eq("errors"),argThat((Map<String,String> m) -> m.containsKey("file")));
        verify(session).removeAttribute("userImport"); verifyNoInteractions(service); verify(dispatcher).forward(request,response);
    }

    @Test void oversizedFileIsRejectedWithoutReading() throws Exception {
        when(request.getPart("file")).thenReturn(file); when(file.getSize()).thenReturn((long)Xlsx.MAX_BYTES+1);
        servlet.doPost(request,response); verify(response).setStatus(400); verify(file,never()).getInputStream(); verifyNoInteractions(service);
    }

    @Test void invalidWorkbookReturnsInlineError() throws Exception {
        when(request.getPart("file")).thenReturn(file); when(file.getSize()).thenReturn(1L); when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        when(service.preview(eq(12L),any())).thenThrow(new IllegalArgumentException("Sai mẫu"));
        servlet.doPost(request,response); verify(response).setStatus(400); verify(request).setAttribute("errors",Map.of("file","Sai mẫu"));
    }

    @Test void confirmationCountsAndClearsPreview() throws Exception {
        var value=preview(); confirm(value);
        when(service.confirm(eq(12L),eq(value),eq(value.getToken()),any())).thenReturn(List.of(value.getLines().get(0),new ImportPreview.Line(3,List.of("bad"),"Tạo mới","Lỗi")));
        servlet.doPost(request,response); verify(request).setAttribute("successCount",1L); verify(request).setAttribute("failureCount",1L); verify(session).removeAttribute("userImport");
    }

    @Test void invalidConfirmationReturnsPreviewError() throws Exception {
        var value=preview(); confirm(value); when(service.confirm(eq(12L),eq(value),anyString(),any())).thenThrow(new IllegalArgumentException("Hết hạn"));
        servlet.doPost(request,response); verify(response).setStatus(400); verify(request).setAttribute("errors",Map.of("preview","Hết hạn")); verify(session).removeAttribute("userImport");
    }

    @Test void systemErrorRemains500AndPreviewIsNotReusable() throws Exception {
        var value=preview(); confirm(value); when(service.confirm(eq(12L),eq(value),anyString(),any())).thenThrow(new IllegalStateException("DB unavailable"));
        servlet.doPost(request,response); verify(response).sendError(500); verify(session).removeAttribute("userImport"); verify(request,never()).setAttribute(eq("errors"),any());
    }

    @Test void revokedPermissionRemains403() throws Exception {
        var value=preview(); confirm(value); when(service.confirm(eq(12L),eq(value),anyString(),any())).thenThrow(new SecurityException());
        servlet.doPost(request,response); verify(response).sendError(403); verify(session).removeAttribute("userImport");
    }

    @Test void invalidCsrfDoesNotCallImportService() throws Exception {
        when(csrf.isValid(session,"valid")).thenReturn(false); servlet.doPost(request,response);
        verify(response).sendError(403); verifyNoInteractions(service); verify(session,never()).removeAttribute("userImport");
    }

    @Test void unexpectedMultipartFailureRemainsSystemError() throws Exception {
        when(request.getContentType()).thenReturn("multipart/form-data; boundary=x"); when(request.getPart("file")).thenThrow(new IllegalStateException("Unexpected parser failure"));
        servlet.doPost(request,response); verify(response).sendError(500); verifyNoInteractions(service);
    }
}
