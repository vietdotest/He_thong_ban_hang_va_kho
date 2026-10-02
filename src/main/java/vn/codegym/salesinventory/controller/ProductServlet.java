package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.math.BigDecimal;
import java.util.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.ProductValidationException;

public final class ProductServlet extends PortalServlet {
    protected ProductService products() { return new ProductService(source()); }
    protected List<Map<String, Object>> categories() { return new CategoryService(source()).tree(); }

    protected void get(HttpServletRequest r, HttpServletResponse s) throws Exception {
        int page = value(r, "page").isEmpty() ? 1 : Math.toIntExact(number(r, "page"));
        r.setAttribute("pageNo", page);
        r.setAttribute("categories", categories());
        r.setAttribute("products", products().list(actor(r).id(), value(r, "q"),
                value(r, "category").isEmpty() ? null : number(r, "category"), value(r, "status"), page));
        if (!value(r, "id").isEmpty()) r.setAttribute("edit", products().find(actor(r).id(), number(r, "id")));
        Object success = r.getSession().getAttribute("productSuccess");
        r.getSession().removeAttribute("productSuccess");
        r.setAttribute("successMessage", success);
        view(r, s, "catalog/products");
    }

    protected void post(HttpServletRequest r, HttpServletResponse s) throws Exception {
        access(r).require("PRODUCT_MANAGE");
        if (r.getParameter("cost") != null) access(r).require("COST_WRITE");
        Map<String, Object> form = form(r);
        Map<String, String> errors = new LinkedHashMap<>();
        long id = identifier(r, "id", true, errors);
        if ("delete".equals(value(r, "action"))) {
            if (id <= 0 || !errors.isEmpty()) throw new IllegalArgumentException("Mã sản phẩm không hợp lệ.");
            products().delete(actor(r).id(), id);
            r.getSession().setAttribute("productSuccess", "Đã xóa sản phẩm.");
            redirect(r, s, "/catalog/products");
            return;
        }
        long category = identifier(r, "category", false, errors);
        long version = identifier(r, "version", id == 0, errors);
        if (id > 0 && version <= 0) errors.put("form", "Phiên bản sản phẩm không hợp lệ. Hãy tải lại.");
        BigDecimal cost = null;
        if (!value(r, "cost").isEmpty()) {
            try {
                if (value(r, "cost").length() > 64) throw new IllegalArgumentException("Giá vốn vượt giới hạn số thập phân.");
                cost = new BigDecimal(value(r, "cost"));
            }
            catch (NumberFormatException e) { errors.put("cost", "Giá vốn phải là số không âm, tối đa 4 chữ số thập phân."); }
            catch (IllegalArgumentException e) { errors.put("cost", e.getMessage()); }
        }
        var input = new ProductService.Input(value(r, "sku"), value(r, "name"), category, value(r, "baseUnit"),
                value(r, "packaging"), cost, null, value(r, "status"), version);
        errors.putAll(ProductService.errors(input));
        try {
            if (!errors.isEmpty()) throw new ProductValidationException(errors);
            Part file = r.getContentType() != null && r.getContentType().toLowerCase(Locale.ROOT).startsWith("multipart/form-data")
                    ? r.getPart("image") : null;
            if (file != null && file.getSize() > ImageStorage.MAX_BYTES)
                throw ProductValidationException.field("image", "Ảnh JPG/PNG phải có dung lượng tối đa 2MB.");
            if (file != null && file.getSize() > 0) {
                try (var stream = file.getInputStream()) {
                    products().saveWithImage(actor(r).id(), id, input, stream.readNBytes(ImageStorage.MAX_BYTES + 1));
                }
            } else products().save(actor(r).id(), id, input);
        } catch (ProductValidationException e) {
            s.setStatus(400);
            r.setAttribute("edit", form);
            r.setAttribute("editing", id > 0);
            r.setAttribute("errors", e.errors());
            r.setAttribute("formError", e.errors().get("form"));
            r.setAttribute("categories", categories());
            r.setAttribute("products", products().list(actor(r).id(), "", null, "", 1));
            r.setAttribute("pageNo", 1);
            view(r, s, "catalog/products");
            return;
        }
        r.getSession().setAttribute("productSuccess", "Đã lưu sản phẩm.");
        redirect(r, s, "/catalog/products");
    }

    private static long identifier(HttpServletRequest r, String name, boolean allowEmpty, Map<String, String> errors) {
        String raw = value(r, name);
        if (allowEmpty && raw.isEmpty()) return 0;
        try {
            long number = Long.parseLong(raw);
            if (number < 0) throw new NumberFormatException();
            return number;
        } catch (NumberFormatException e) {
            errors.put(name.equals("category") ? "category" : "form", "Mã hoặc phiên bản không hợp lệ.");
            return 0;
        }
    }
    private Map<String, Object> form(HttpServletRequest r) {
        Map<String, Object> form = new LinkedHashMap<>();
        for (String name : List.of("id", "version", "sku", "name", "packaging", "status"))
            form.put(name, raw(r, name));
        form.put("category_id", raw(r, "category"));
        form.put("base_unit", raw(r, "baseUnit"));
        if (access(r).allows("COST_READ")) form.put("cost_price", raw(r, "cost"));
        return form;
    }
    private static String raw(HttpServletRequest r, String name) {
        return Objects.requireNonNullElse(r.getParameter(name), "");
    }
}
