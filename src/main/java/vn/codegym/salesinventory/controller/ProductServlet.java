package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.math.BigDecimal;
import java.util.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.ProductValidationException;

public final class ProductServlet extends PortalServlet {
    protected ProductService products() { return new ProductService(source()); }
    protected List<Map<String, Object>> categories() { return new CategoryService(source()).tree(); }
    private static String filter(HttpServletRequest r,String key,boolean post){return value(r,post&&Set.of("category","status").contains(key)?"filter"+Character.toUpperCase(key.charAt(0))+key.substring(1):key);}
    private static String returnPath(HttpServletRequest r,boolean post){
        var page=vn.codegym.salesinventory.dto.PageRequest.parse(value(r,"page"),value(r,"pageSize"));var params=new ArrayList<String>();
        for(String key:List.of("q","category","status")){String v=filter(r,key,post);if(!v.isEmpty())params.add(key+"="+java.net.URLEncoder.encode(v,java.nio.charset.StandardCharsets.UTF_8));}
        if(!value(r,"page").isEmpty()||!value(r,"pageSize").isEmpty()){params.add("page="+page.page());params.add("pageSize="+page.pageSize());}
        return "/catalog/products"+(params.isEmpty()?"":"?"+String.join("&",params));
    }
    private void list(HttpServletRequest r,boolean post){
        var filters=new LinkedHashMap<String,String>();for(String key:List.of("q","category","status"))filters.put(key,filter(r,key,post));
        Long category=null;try{if(!filters.get("category").isEmpty()){category=Long.valueOf(filters.get("category"));if(category<=0)category=0L;}}catch(NumberFormatException invalid){category=0L;}
        var result=products().search(actor(r).id(),filters.get("q"),category,filters.get("status"),vn.codegym.salesinventory.dto.PageRequest.parse(value(r,"page"),value(r,"pageSize")));
        r.setAttribute("products",result.items());r.setAttribute("pagination",result);r.setAttribute("productFilter",filters);r.setAttribute("paginationFilterValues",filters);r.setAttribute("productReturn",returnPath(r,post));
        r.setAttribute("pageNo",result.page());r.setAttribute("totalProducts",result.totalItems());r.setAttribute("hasNext",result.page()<result.getTotalPages());
    }

    protected void get(HttpServletRequest r, HttpServletResponse s) throws Exception {
        r.setAttribute("categories", categories());
        list(r,false);
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
            redirect(r, s, returnPath(r,true));
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
            list(r,true);
            view(r, s, "catalog/products");
            return;
        }
        r.getSession().setAttribute("productSuccess", "Đã lưu sản phẩm.");
        redirect(r, s, returnPath(r,true));
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
