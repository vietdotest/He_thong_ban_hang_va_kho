package vn.codegym.salesinventory.controller;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.*;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.*;
import vn.codegym.salesinventory.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ProductImportServletTest extends CatalogControllerTestSupport {
    ProductImportService service;ProductImportServlet servlet;
    @BeforeEach void controller()throws Exception{service=mock(ProductImportService.class);servlet=new ProductImportServlet(service);init(servlet);when(request.getSession()).thenReturn(session);}
    @Test void uploadProducesCountsAndStoresPreview()throws Exception{var file=mock(Part.class);when(request.getPart("file")).thenReturn(file);when(file.getSize()).thenReturn(4L);when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[]{1,2,3,4}));var preview=new ImportPreview(12,List.of(new ImportPreview.Line(2,List.of("SKU"),"Tạo mới","")),Instant.now());when(service.preview(eq(12L),any())).thenReturn(preview);servlet.doPost(request,response);verify(session).setAttribute("productImport",preview);assertThat(attributes.get("validCount")).isEqualTo(1L);}
    @Test void emptyUploadIsFieldError400()throws Exception{servlet.doPost(request,response);verify(response).setStatus(400);assertThat(((Map<?,?>)attributes.get("errors")).get("file")).isNotNull();}
    @Test void confirmClearsSessionAndSummarizes()throws Exception{var preview=new ImportPreview(12,List.of(),Instant.now());when(session.getAttribute("productImport")).thenReturn(preview);params.putAll(Map.of("action","confirm","token",preview.getToken()));when(service.confirm(12,preview,preview.getToken())).thenReturn(List.of(new ImportPreview.Line(2,List.of(),"Tạo mới",""),new ImportPreview.Line(3,List.of(),"Cập nhật","Đã đổi")));servlet.doPost(request,response);verify(session).removeAttribute("productImport");assertThat(attributes.get("successCount")).isEqualTo(1L);assertThat(attributes.get("failureCount")).isEqualTo(1L);}
    @Test void stalePreview400AndRemoved()throws Exception{var preview=new ImportPreview(12,List.of(),Instant.now());when(session.getAttribute("productImport")).thenReturn(preview);params.put("action","confirm");doThrow(new IllegalArgumentException("Hết hạn")).when(service).confirm(anyLong(),any(),anyString());servlet.doPost(request,response);verify(response).setStatus(400);verify(session).removeAttribute("productImport");}
    @Test void permissionRevokedIs403NotRowError()throws Exception{noPermissions();servlet.doPost(request,response);verify(response).sendError(403);verifyNoInteractions(service);}
    @Test void badCsrfIs403()throws Exception{params.put("_csrf","bad");servlet.doPost(request,response);verify(response).sendError(403);verifyNoInteractions(service);}
    @Test void systemFailure500ClearsPreview()throws Exception{var preview=new ImportPreview(12,List.of(),Instant.now());when(session.getAttribute("productImport")).thenReturn(preview);params.put("action","confirm");doThrow(new IllegalStateException("database")).when(service).confirm(anyLong(),any(),anyString());servlet.doPost(request,response);verify(response).sendError(500);verify(session).removeAttribute("productImport");}
}
