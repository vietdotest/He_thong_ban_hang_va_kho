package vn.codegym.salesinventory.controller;
import java.io.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.security.Access;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.FieldValidationException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class OrderServletTest extends CatalogControllerTestSupport {
    OrderService service;OrderServlet servlet;
    @BeforeEach void controller()throws Exception{service=mock(OrderService.class);var dealers=mock(DealerService.class);var products=mock(ProductService.class);when(products.search(anyLong(),anyString(),any(),anyString(),any())).thenReturn(new PageResult<>(List.of(),0,1,20));when(dealers.find(anyLong(),anyLong())).thenReturn(Map.of("id",3,"code","QA","name","Đại lý QA"));servlet=new OrderServlet(service,dealers,products);init(servlet);
        attributes.put("access",new Access(Set.of("SALES_MANAGER"),Set.of("ORDER_READ","ORDER_WRITE"),List.of(),List.of(),List.of()));params.putAll(Map.of("action","save","dealer","3","address","4","delivery","2030-01-03","version","0","creationKey",UUID.randomUUID().toString()));when(request.getParameterValues("product")).thenReturn(new String[]{"5","5"});when(request.getParameterValues("unit")).thenReturn(new String[]{"6","7"});when(request.getParameterValues("quantity")).thenReturn(new String[]{"12","1.500001"});}
    @Test void trustedActorAndExactQuantitiesReachServiceWithoutClientTotal()throws Exception{params.put("net_total","1");when(service.save(anyLong(),anyLong(),any())).thenReturn(9L);servlet.doPost(request,response);var input=ArgumentCaptor.forClass(OrderService.Input.class);verify(service).save(eq(12L),eq(0L),input.capture());assertThat(input.getValue().lines()).hasSize(2);assertThat(input.getValue().lines().get(1).quantity()).isEqualByComparingTo("1.500001");verify(response).setStatus(303);}
    @Test void malformedRowRetainsRawInputAndDoesNotCallService()throws Exception{when(request.getParameterValues("quantity")).thenReturn(new String[]{"bad","1.500001"});servlet.doPost(request,response);verify(response).setStatus(400);assertThat(((Map<?,?>)attributes.get("errors")).containsKey("line0")).isTrue();@SuppressWarnings("unchecked")var rows=(List<Map<String,Object>>)attributes.get("lineForms");assertThat(rows.get(0)).containsEntry("quantity","bad");verify(service,never()).save(anyLong(),anyLong(),any());}
    @Test void mismatchedArraysAre400NotPartialOrder()throws Exception{when(request.getParameterValues("unit")).thenReturn(new String[]{"6"});servlet.doPost(request,response);verify(response).setStatus(400);verify(service,never()).save(anyLong(),anyLong(),any());}
    @Test void serviceFieldErrorRetainsPostedVersion()throws Exception{params.put("id","9");params.put("version","1");when(service.find(12,9)).thenReturn(Map.of("id",9,"dealer_id",3,"address_id",4,"desired_delivery","2030-01-03","version",2,"can_edit",true,"lines",List.of()));doThrow(FieldValidationException.field("form","Đã thay đổi")).when(service).save(anyLong(),anyLong(),any());servlet.doPost(request,response);verify(response).setStatus(400);assertThat(((Map<?,?>)attributes.get("form")).get("version")).isEqualTo("1");assertThat(attributes.get("unsaved")).isEqualTo(true);}
    @Test void csrfAndPermissionDenyWrites()throws Exception{noPermissions();servlet.doPost(request,response);verify(response).sendError(403);verifyNoInteractions(service);}
    @Test void listUsesBoundedParsedPageAndSameActor()throws Exception{params.clear();params.put("page","bad");params.put("pageSize","999");when(service.search(anyLong(),anyString(),anyString(),any(),any())).thenReturn(new PageResult<>(List.of(),0,1,20));servlet.doGet(request,response);verify(service).search(eq(12L),eq(""),eq(""),isNull(),eq(new PageRequest(1,20)));}
    @Test void suggestionsUseScopedServiceAndNeverFullUserOrProductProjection()throws Exception{when(request.getServletPath()).thenReturn("/api/orders/options");params.put("product","5");when(service.units(12,3,5)).thenReturn(List.of(Map.of("id",6,"name","Lon","factor",BigDecimal.ONE)));var output=new StringWriter();when(response.getWriter()).thenReturn(new PrintWriter(output));servlet.doGet(request,response);verify(service).units(12,3,5);assertThat(output.toString()).contains("Lon").doesNotContain("cost_price","floor_price","owner_id");}
    @Test void previewReturnsDecimalStringsAndRejectsTamperedLines()throws Exception{params.put("action","preview");var quote=new OrderService.Quote(LocalDate.of(2030,1,1),List.of(),new BigDecimal("100.0001"),BigDecimal.ZERO,new BigDecimal("100.0001"),Map.of(),"","digest","",false);when(service.preview(anyLong(),any())).thenReturn(quote);var output=new StringWriter();when(response.getWriter()).thenReturn(new PrintWriter(output));servlet.doPost(request,response);assertThat(output.toString()).contains("\"net\":\"100.0001\"");}
}
