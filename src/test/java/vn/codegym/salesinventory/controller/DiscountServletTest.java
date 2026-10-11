package vn.codegym.salesinventory.controller;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.security.Access;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.FieldValidationException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DiscountServletTest extends CatalogControllerTestSupport {
    DiscountService service;DiscountServlet servlet;
    @BeforeEach void controller()throws Exception{service=mock(DiscountService.class);var pricing=mock(PricingService.class);var categories=mock(CategoryService.class);var products=mock(ProductService.class);servlet=new DiscountServlet(service,pricing,categories,products);init(servlet);attributes.put("access",new Access(Set.of("SALES_MANAGER"),Set.of("DISCOUNT_READ","DISCOUNT_MANAGE"),List.of(),List.of(),List.of()));when(service.search(anyLong(),anyString(),any())).thenReturn(new PageResult<>(List.of(),0,1,20));params.putAll(Map.of("name","Ưu đãi","targetType","SKU","product","2","mode","PERCENT","from","2026-10-01","to","2026-12-31","status","ACTIVE"));when(request.getParameterValues("minimum")).thenReturn(new String[]{"24","48"});when(request.getParameterValues("discountValue")).thenReturn(new String[]{"5","10"});}
    @Test void actorAndBothTiersReachService()throws Exception{servlet.doPost(request,response);var input=ArgumentCaptor.forClass(DiscountService.Input.class);verify(service).save(eq(12L),eq(0L),input.capture());assertThat(input.getValue().tiers()).hasSize(2);assertThat(input.getValue().product()).isEqualTo(2L);assertThat(input.getValue().category()).isNull();verify(response).setStatus(303);}
    @Test void invalidTierRetainsRawInput()throws Exception{when(request.getParameterValues("discountValue")).thenReturn(new String[]{"bad","10"});servlet.doPost(request,response);verify(response).setStatus(400);assertThat(((Map<?,?>)attributes.get("errors")).containsKey("value0")).isTrue();assertThat(((Map<?,?>)attributes.get("edit")).get("name")).isEqualTo("Ưu đãi");verify(service,never()).save(anyLong(),anyLong(),any());}
    @Test void dateErrorAndStaleRevisionDoNotResetPostedRevision()throws Exception{params.put("from","wrong");servlet.doPost(request,response);verify(response).setStatus(400);assertThat(((Map<?,?>)attributes.get("errors")).containsKey("from")).isTrue();}
    @Test void permissionAndCsrfDenyWrites()throws Exception{noPermissions();servlet.doPost(request,response);verify(response).sendError(403);verify(service,never()).save(anyLong(),anyLong(),any());}
    @Test void duplicateThresholdBusinessErrorIs400()throws Exception{doThrow(FieldValidationException.field("minimum1","Trùng mốc")).when(service).save(anyLong(),anyLong(),any());servlet.doPost(request,response);verify(response).setStatus(400);assertThat(((Map<?,?>)attributes.get("errors")).containsKey("minimum1")).isTrue();}
}
