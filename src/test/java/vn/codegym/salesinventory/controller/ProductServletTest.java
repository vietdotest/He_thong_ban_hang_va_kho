package vn.codegym.salesinventory.controller;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.ByteArrayInputStream;
import java.util.*;
import org.junit.jupiter.api.*;
import vn.codegym.salesinventory.config.ApplicationContextKeys;
import vn.codegym.salesinventory.security.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.ProductValidationException;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ProductServletTest {
    ProductServlet servlet;
    ProductService products;
    HttpServletRequest request;
    HttpServletResponse response;
    HttpSession session;
    ServletContext context;
    RequestDispatcher dispatcher;
    CsrfTokenManager csrf;
    Map<String, String> parameters;
    Map<String, Object> attributes;

    @BeforeEach void setup() throws Exception {
        products = mock(ProductService.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        session = mock(HttpSession.class);
        context = mock(ServletContext.class);
        dispatcher = mock(RequestDispatcher.class);
        csrf = mock(CsrfTokenManager.class);
        parameters = new HashMap<>(Map.of("sku", "NEW-SKU", "name", "Tên mới", "category", "2", "baseUnit", "Lon", "packaging", "Thùng", "status", "ACTIVE", "_csrf", "csrf"));
        attributes = new HashMap<>();
        attributes.put("access", new Access(Set.of("SALES_MANAGER"), Set.of("PRODUCT_MANAGE", "CATALOG_READ", "COST_READ", "COST_WRITE"), List.of(), List.of(), List.of()));
        when(request.getParameter(anyString())).thenAnswer(i -> parameters.get(i.getArgument(0)));
        when(request.getAttribute(anyString())).thenAnswer(i -> attributes.get(i.getArgument(0)));
        doAnswer(i -> { attributes.put(i.getArgument(0), i.getArgument(1)); return null; }).when(request).setAttribute(anyString(), any());
        when(request.getSession(false)).thenReturn(session);
        when(request.getSession()).thenReturn(session);
        when(session.getAttribute(SessionKeys.CURRENT_USER)).thenReturn(new CurrentUser(7, "manager", "manager@test.local", "Quản lý"));
        when(context.getAttribute(ApplicationContextKeys.CSRF_TOKEN_MANAGER)).thenReturn(csrf);
        when(csrf.isValid(session, "csrf")).thenReturn(true);
        when(request.getContextPath()).thenReturn("");
        when(request.getContentType()).thenReturn("multipart/form-data; boundary=test");
        when(request.getRequestURI()).thenReturn("/catalog/products");
        when(request.getRequestDispatcher(anyString())).thenReturn(dispatcher);
        ServletConfig config = mock(ServletConfig.class);
        when(config.getServletContext()).thenReturn(context);
        servlet = spy(new ProductServlet());
        servlet.init(config);
        doReturn(products).when(servlet).products();
        doReturn(List.of(Map.of("id", 2L, "label", "Nhóm"))).when(servlet).categories();
        when(products.list(7, "", null, "", 1)).thenReturn(List.of());
        when(products.search(eq(7L),anyString(),nullable(Long.class),anyString(),any())).thenReturn(new vn.codegym.salesinventory.dto.PageResult<>(List.of(),0,1,20));
    }
    @Test void invalidFormReturns400AndPreservesRawValues() throws Exception {
        parameters.put("sku", "BAD SKU"); parameters.put("name", "  <script>alert(1)</script>  ");
        parameters.put("category", "wrong"); parameters.put("cost", "-1");
        servlet.doPost(request, response);
        verify(response).setStatus(400);
        verify(dispatcher).forward(request, response);
        assertThat(map("edit")).containsValue("  <script>alert(1)</script>  ");
        assertThat(map("errors").keySet()).containsAll(List.of("sku", "category", "cost"));
        verify(products, never()).save(anyLong(), anyLong(), any());
        verify(request, never()).getPart("image");
    }
    @Test void duplicateSkuKeepsBothContactAndOtherProductFields() throws Exception {
        when(products.save(eq(7L), eq(0L), any())).thenThrow(ProductValidationException.field("sku", "SKU đã tồn tại."));
        servlet.doPost(request, response);
        verify(response).setStatus(400);
        assertThat(map("edit")).containsValue("Tên mới").containsValue("Thùng");
        assertThat(map("errors")).containsValue("SKU đã tồn tại.");
    }
    @Test void successUsesSessionActorAnd303() throws Exception {
        parameters.put("userId", "99"); parameters.put("actor", "99"); parameters.put("imageKey", "../fake");
        servlet.doPost(request, response);
        verify(products).save(eq(7L), eq(0L), argThat(in -> in.imageKey() == null));
        verify(response).setStatus(303);
        verify(response).setHeader("Location", "/catalog/products");
        verify(session).setAttribute("productSuccess", "Đã lưu sản phẩm.");
    }
    @Test void missingOrWrongCsrfIs403() throws Exception {
        parameters.remove("_csrf"); servlet.doPost(request, response);
        parameters.put("_csrf", "wrong"); servlet.doPost(request, response);
        verify(response, times(2)).sendError(403);
        verify(products, never()).save(anyLong(), anyLong(), any());
    }
    @Test void productWriteWithoutPermissionIs403() throws Exception {
        attributes.put("access", new Access(Set.of("SALES"), Set.of("CATALOG_READ"), List.of(), List.of(), List.of()));
        servlet.doPost(request, response);
        verify(response).sendError(403);
        verify(products, never()).save(anyLong(), anyLong(), any());
    }
    @Test void customProductPermissionCannotForgeCostEvenIfBlank() throws Exception {
        attributes.put("access", new Access(Set.of("ADMIN"), Set.of("PRODUCT_MANAGE", "CATALOG_READ", "COST_WRITE"), List.of(), List.of(), List.of()));
        parameters.put("cost", "");
        servlet.doPost(request, response);
        verify(response).sendError(403);
        verify(products, never()).save(anyLong(), anyLong(), any());
        assertThat(attributes).doesNotContainKey("edit");
    }
    @Test void staleVersionReturnsFormErrorAndPreservesVersion() throws Exception {
        parameters.put("id", "42"); parameters.put("version", "1");
        when(products.save(eq(7L), eq(42L), any())).thenThrow(ProductValidationException.field("form", "Sản phẩm đã thay đổi."));
        servlet.doPost(request, response);
        verify(response).setStatus(400);
        assertThat(attributes.get("formError")).isEqualTo("Sản phẩm đã thay đổi.");
        assertThat(((Map<?, ?>) attributes.get("edit")).get("version")).isEqualTo("1");
    }
    @Test void databaseFailureRemains500() throws Exception {
        when(products.save(anyLong(), anyLong(), any())).thenThrow(new IllegalStateException("database"));
        servlet.doPost(request, response);
        verify(response).sendError(500);
        verify(response, never()).setStatus(400);
    }
    @Test void hugeExponentCostIsRejectedWithoutDecimalExpansion() throws Exception {
        parameters.put("cost", "1E999999999");
        servlet.doPost(request, response);
        verify(response).setStatus(400);
        assertThat(map("errors")).containsKey("cost");
        verify(products, never()).save(anyLong(), anyLong(), any());
    }
    @Test void invalidCostTextIsRetained() throws Exception {
        parameters.put("cost", "not-a-number");
        servlet.doPost(request, response);
        verify(response).setStatus(400);
        assertThat(map("edit")).containsEntry("cost_price", "not-a-number");
        assertThat(map("errors")).containsEntry("cost", "Giá vốn phải là số không âm, tối đa 4 chữ số thập phân.");
    }
    @Test void urlEncodedPostWithoutImageIsAlsoAccepted() throws Exception {
        when(request.getContentType()).thenReturn("application/x-www-form-urlencoded");
        servlet.doPost(request, response);
        verify(products).save(eq(7L), eq(0L), any());
        verify(request, never()).getPart("image");
        verify(response).setStatus(303);
    }
    @Test void oversizedImageReturnsFieldErrorWithoutSaving() throws Exception {
        Part image = mock(Part.class); when(request.getPart("image")).thenReturn(image);
        when(image.getSize()).thenReturn((long) ImageStorage.MAX_BYTES + 1);
        servlet.doPost(request, response);
        verify(response).setStatus(400);
        assertThat(((Map<?, ?>) attributes.get("errors")).get("image")).isNotNull();
        verify(products, never()).saveWithImage(anyLong(), anyLong(), any(), any());
    }
    @Test void uploadedImageIsHandledByTransactionalService() throws Exception {
        Part image = mock(Part.class); when(request.getPart("image")).thenReturn(image);
        byte[] bytes = {1, 2, 3}; when(image.getSize()).thenReturn(3L);
        when(image.getInputStream()).thenReturn(new ByteArrayInputStream(bytes));
        servlet.doPost(request, response);
        verify(products).saveWithImage(eq(7L), eq(0L), any(), eq(bytes));
        verify(response).setStatus(303);
    }
    @SuppressWarnings("unchecked") private Map<String, Object> map(String name) {
        return (Map<String, Object>) attributes.get(name);
    }
    @Test void invalidIdentifiersAreFieldErrorsInsteadOfChangingTarget() throws Exception {
        parameters.put("id", "not-an-id"); parameters.put("version", "-1");
        servlet.doPost(request, response);
        verify(response).setStatus(400);
        assertThat(((Map<?, ?>) attributes.get("errors")).get("form")).isNotNull();
        verify(products, never()).save(anyLong(), anyLong(), any());
    }
}
