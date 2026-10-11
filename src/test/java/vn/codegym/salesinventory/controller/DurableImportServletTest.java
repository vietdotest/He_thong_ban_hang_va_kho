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
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.security.*;
import vn.codegym.salesinventory.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DurableImportServletTest {
    @Mock HttpServletRequest r;@Mock HttpServletResponse s;@Mock HttpSession session;@Mock ServletContext context;@Mock ServletConfig config;@Mock RequestDispatcher dispatcher;@Mock CsrfTokenManager csrf;@Mock Part file;@Mock ImportJobService jobs;
    final String id=UUID.randomUUID().toString();DurableImportServlet servlet;ImportPreview preview;
    @BeforeEach void setup()throws Exception{preview=new ImportPreview(12,List.of(new ImportPreview.Line(2,List.of("new-user"),"Tạo mới","")),Instant.now());servlet=new DurableImportServlet(){protected String importKind(){return "USER";}protected ImportPreview readPreview(long actor,byte[] bytes){assertThat(actor).isEqualTo(12);return preview;}};
        lenient().when(r.getParameter(anyString())).thenReturn(null);
        lenient().when(config.getServletContext()).thenReturn(context);servlet.init(config);lenient().when(r.getSession()).thenReturn(session);lenient().when(r.getSession(false)).thenReturn(session);lenient().when(r.getContextPath()).thenReturn("");lenient().when(r.getRequestURI()).thenReturn("/admin/users/import");lenient().when(r.getAttribute("access")).thenReturn(new Access(Set.of("ADMIN"),Set.of("USER_MANAGE"),List.of(),List.of(),List.of()));lenient().when(session.getAttribute(SessionKeys.CURRENT_USER)).thenReturn(new CurrentUser(12,"actor","actor@qa.local","QA"));lenient().when(context.getAttribute(ApplicationContextKeys.IMPORT_JOB_SERVICE)).thenReturn(jobs);lenient().when(context.getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER)).thenReturn(csrf);lenient().when(r.getParameter("_csrf")).thenReturn("valid");lenient().when(csrf.isValid(session,"valid")).thenReturn(true);lenient().when(r.getRequestDispatcher("/WEB-INF/views/imports/job.jsp")).thenReturn(dispatcher);
    }
    void selected(){when(r.getParameter("job")).thenReturn(id);}
    Map<String,Object> job(){return Map.of("id",id,"status","PREVIEW","headers",UserImportService.HEADERS,"confirm_key","server-key","version",1L);}
    @Test void uploadPersistsTaskThenRedirectsAndOnlyStoresUuid()throws Exception{when(r.getPart("file")).thenReturn(file);when(file.getSize()).thenReturn(2L);when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1,2}));when(file.getSubmittedFileName()).thenReturn("input.xlsx");when(jobs.create(12,"USER","input.xlsx",preview)).thenReturn(id);servlet.doPost(r,s);verify(session).setAttribute("userImportJobId",id);verify(session,never()).setAttribute(eq("userImport"),any());verify(s).setStatus(303);verify(s).setHeader("Location","/admin/users/import?job="+id);verify(session).removeAttribute("userImportReport");verifyNoInteractions(dispatcher);}
    @Test void confirmQueuesAllRowsOnceAndPreservesFilters()throws Exception{selected();when(r.getParameter("action")).thenReturn("confirm");when(r.getParameter("version")).thenReturn("1");when(r.getParameter("token")).thenReturn("server-key");when(r.getParameter("q")).thenReturn("Tên dài");when(r.getParameter("state")).thenReturn("READY");when(r.getParameter("page")).thenReturn("2");servlet.doPost(r,s);verify(jobs).confirm(12,"USER",id,1,"server-key");verify(s).setHeader(eq("Location"),argThat(url->url.contains("state=READY")&&url.contains("page=2")&&url.contains("q=T%C3%AAn+d%C3%A0i")));verifyNoInteractions(dispatcher);}
    @Test void serverPaginationClampsMalformedPageAndKeepsJobId()throws Exception{selected();when(r.getParameter("page")).thenReturn("garbage");when(r.getParameter("pageSize")).thenReturn("999");when(jobs.find(12,"USER",id)).thenReturn(job());when(jobs.rows(12,"USER",id,"","",new PageRequest(1,20))).thenReturn(new PageResult<>(List.of(),0,1,20));servlet.doGet(r,s);verify(r).setAttribute("job",job());verify(r).setAttribute(eq("pagination"),any(PageResult.class));verify(dispatcher).forward(r,s);verify(session,never()).setAttribute(anyString(),any());}
    @Test void staleConfirmReturnsFieldErrorAndRetainsTheCurrentTask()throws Exception{selected();when(r.getParameter("action")).thenReturn("confirm");when(r.getParameter("version")).thenReturn("1");when(r.getParameter("token")).thenReturn("server-key");doThrow(new IllegalArgumentException("Xem trước đã thay đổi")).when(jobs).confirm(12,"USER",id,1,"server-key");when(jobs.find(12,"USER",id)).thenReturn(job());when(jobs.rows(eq(12L),eq("USER"),eq(id),anyString(),anyString(),any())).thenReturn(new PageResult<>(List.of(),0,1,20));servlet.doPost(r,s);verify(s).setStatus(400);verify(r).setAttribute("errors",Map.of("preview","Xem trước đã thay đổi"));verify(dispatcher).forward(r,s);verify(session,never()).removeAttribute("userImportJobId");}
    @Test void foreignTaskOrRevokedPermissionReturns403()throws Exception{selected();when(jobs.find(12,"USER",id)).thenThrow(new SecurityException());servlet.doGet(r,s);verify(s).sendError(403);verifyNoInteractions(dispatcher);}
    @Test void invalidUuidIs400NotDatabaseCallOr500()throws Exception{when(r.getParameter("job")).thenReturn("../../other");servlet.doGet(r,s);verify(s).setStatus(400);verifyNoInteractions(jobs);verify(dispatcher).forward(r,s);}
    @Test void csrfRejectsConfirmationWithoutQueueing()throws Exception{when(csrf.isValid(session,"valid")).thenReturn(false);servlet.doPost(r,s);verify(s).sendError(403);verifyNoInteractions(jobs);}
    @Test void progressJsonContainsScopedRowsNotSecrets()throws Exception{selected();when(r.getParameter("progress")).thenReturn("1");when(jobs.progress(12,"USER",id)).thenReturn(Map.of("id",id,"status","RUNNING","success_count",1));when(jobs.rows(eq(12L),eq("USER"),eq(id),anyString(),anyString(),any())).thenReturn(new PageResult<>(List.of(Map.of("number",2,"state","SUCCESS","cells",List.of("test"))),21,2,20));var output=new StringWriter();when(s.getWriter()).thenReturn(new PrintWriter(output));servlet.doGet(r,s);assertThat(output.toString()).contains("\"status\":\"RUNNING\"","\"page\":2","\"totalItems\":21").doesNotContain("password","confirm_key","row_token","lease_owner");verify(s).setHeader("Cache-Control","no-store");verifyNoInteractions(dispatcher);}
}
