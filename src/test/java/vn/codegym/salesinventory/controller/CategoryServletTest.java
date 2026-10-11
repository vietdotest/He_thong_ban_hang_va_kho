package vn.codegym.salesinventory.controller;
import java.util.Map;
import org.junit.jupiter.api.*;
import vn.codegym.salesinventory.service.CategoryService;
import vn.codegym.salesinventory.validation.FieldValidationException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CategoryServletTest extends CatalogControllerTestSupport {
    CategoryService service;CategoryServlet servlet;
    @BeforeEach void controller() throws Exception {service=mock(CategoryService.class);servlet=new CategoryServlet(service);init(servlet);params.putAll(Map.of("code"," C ","name"," Tên "));}
    @Test void validPostUsesSessionActorAndRedirects303() throws Exception {params.put("userId","99");servlet.doPost(request,response);verify(service).save(12,0,"C","Tên",null,0);verify(response).setStatus(303);}
    @Test void validationRetainsRawFieldsAndShowsNamedErrors() throws Exception {
        doThrow(FieldValidationException.field("name","Tên sai")).when(service).save(12,0,"C","Tên",null,0);
        servlet.doPost(request,response);verify(response).setStatus(400);assertThat(((Map<?,?>)attributes.get("edit")).get("name")).isEqualTo(" Tên ");assertThat(((Map<?,?>)attributes.get("errors")).get("name")).isNotNull();verify(dispatcher).forward(request,response);
    }
    @Test void invalidIdIs400AndDoesNotCallSave() throws Exception {params.put("id","<script>");servlet.doPost(request,response);verify(response).setStatus(400);verify(service,never()).save(anyLong(),anyLong(),anyString(),anyString(),any(),anyLong());}
    @Test void deniedPermissionOrCsrfCannotModify() throws Exception {noPermissions();servlet.doPost(request,response);verify(response).sendError(403);verifyNoInteractions(service);}
    @Test void wrongCsrfCannotModify() throws Exception {params.put("_csrf","wrong");servlet.doPost(request,response);verify(response).sendError(403);verifyNoInteractions(service);}
    @Test void databaseFailureIs500() throws Exception {doThrow(new IllegalStateException("database down")).when(service).save(12,0,"C","Tên",null,0);servlet.doPost(request,response);verify(response).sendError(500);}
    @Test void filteredTreeUsesCurrentActorAndSavePreservesFilter()throws Exception{params.put("q","Đồ & hộp");servlet.doGet(request,response);verify(service).tree(12,"Đồ & hộp");servlet.doPost(request,response);verify(response).setHeader("Location","/catalog/categories?q=%C4%90%E1%BB%93+%26+h%E1%BB%99p&notice=saved");}
}
