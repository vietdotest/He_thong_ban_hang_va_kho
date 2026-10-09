package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.*;
import vn.codegym.salesinventory.dto.PageRequest;
import vn.codegym.salesinventory.dao.Sql;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class UnitServlet extends PortalServlet {
    private final UnitService configured;private final ProductService products;
    public UnitServlet(){configured=null;products=null;}
    UnitServlet(UnitService configured,ProductService products){this.configured=configured;this.products=products;}
    private UnitService service(){return configured==null?new UnitService(source()):configured;}
    private ProductService products(){return products==null?new ProductService(source()):products;}
    private void render(HttpServletRequest r,HttpServletResponse s,long product)throws Exception {
        var options=new ArrayList<Map<String,Object>>(products().list(actor(r).id(),value(r,"productQuery"),null,"",1));
        if(product>0&&options.stream().noneMatch(p->Sql.id(p.get("id"))==product))options.add(products().find(actor(r).id(),product));
        r.setAttribute("products",options);r.setAttribute("productId",product);
        if(product>0){var page=service().search(actor(r).id(),product,value(r,"q"),PageRequest.parse(value(r,"page"),value(r,"pageSize")));r.setAttribute("units",page.items());r.setAttribute("pagination",page);}
        r.setAttribute("paginationExtraParameters",Map.of("product",String.valueOf(product),"productQuery",value(r,"productQuery")));
        r.setAttribute("unitReturn",returnPath(r,product));r.setAttribute("warehouses",access(r).warehouses());view(r,s,"catalog/units");
    }
    private static String returnPath(HttpServletRequest r,long product){var page=PageRequest.parse(value(r,"page"),value(r,"pageSize"));return "/catalog/units?product="+product+"&q="+URLEncoder.encode(value(r,"q"),StandardCharsets.UTF_8)+"&productQuery="+URLEncoder.encode(value(r,"productQuery"),StandardCharsets.UTF_8)+"&page="+page.page()+"&pageSize="+page.pageSize();}
    protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception {
        access(r).require("CATALOG_READ");long product=0;
        try {
            product=value(r,"product").isEmpty()?0:number(r,"product");
            if(!value(r,"edit").isEmpty()) {
                var edit=service().find(actor(r).id(),number(r,"edit"));
                if(vn.codegym.salesinventory.dao.Sql.id(edit.get("product_id"))!=product)throw new IllegalArgumentException("Đơn vị không thuộc sản phẩm đã chọn.");
                r.setAttribute("edit",edit);
            }
            if(value(r,"action").equals("convert")) {
                java.math.BigDecimal quantity;try{quantity=CatalogValidation.decimal(value(r,"quantity"),6,false);}catch(IllegalArgumentException invalid){throw FieldValidationException.field("quantity",invalid.getMessage());}
                var converted=service().convert(actor(r).id(),number(r,"id"),quantity);
                if(converted.productId()!=product)throw new IllegalArgumentException("Đơn vị không thuộc sản phẩm đã chọn.");
                r.setAttribute("conversion",converted.quantity()+" "+converted.unitName()+" = "+converted.baseQuantity()+" đơn vị cơ sở (hệ số "+converted.factor()+", phiên bản "+converted.version()+")");
            }
        }catch(IllegalArgumentException invalid){s.setStatus(400);r.setAttribute("errors",fieldErrors(invalid));}
        render(r,s,product);
    }
    protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception {
        access(r).require("WAREHOUSE_MANAGE");long product=0;
        var form=formValues(r,"id","product","name","factor","warehouse","version");form.put("warehouse_id",form.remove("warehouse"));
        try {
            product=number(r,"product");long id=value(r,"id").isEmpty()?0:number(r,"id");
            if(value(r,"action").equals("delete"))service().delete(actor(r).id(),id,product);
            else {
                java.math.BigDecimal factor;try{factor=CatalogValidation.decimal(value(r,"factor"),6,true);}catch(IllegalArgumentException invalid){throw FieldValidationException.field("factor",invalid.getMessage());}
                service().save(actor(r).id(),id,product,value(r,"name"),factor,number(r,"warehouse"),value(r,"version").isEmpty()?0:number(r,"version"));
            }
            redirect(r,s,returnPath(r,product)+"&notice=saved");
        }catch(IllegalArgumentException invalid){s.setStatus(400);r.setAttribute("edit",form);r.setAttribute("errors",fieldErrors(invalid));render(r,s,product);}
    }
}
