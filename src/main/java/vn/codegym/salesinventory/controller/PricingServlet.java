package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.validation.*;
public final class PricingServlet extends PortalServlet {
    private final PricingService configured;private final ProductService configuredProducts;
    public PricingServlet(){configured=null;configuredProducts=null;}
    PricingServlet(PricingService service,ProductService products){configured=service;configuredProducts=products;}
    private PricingService service(){return configured==null?new PricingService(source()):configured;}
    private ProductService products(){return configuredProducts==null?new ProductService(source()):configuredProducts;}
    private LocalDate date(HttpServletRequest r,String key){
        try{return LocalDate.parse(value(r,key));}catch(DateTimeException invalid){throw FieldValidationException.field(key,"Ngày hiệu lực không hợp lệ.");}
    }
    private BigDecimal decimal(HttpServletRequest r,String key){
        try{return CatalogValidation.decimal(value(r,key),4,false);}catch(IllegalArgumentException invalid){throw FieldValidationException.field(key,invalid.getMessage());}
    }
    private long fieldId(HttpServletRequest r,String key){
        try{return number(r,key);}catch(IllegalArgumentException invalid){throw FieldValidationException.field(key,"Mã bản ghi không hợp lệ.");}
    }
    private void options(HttpServletRequest r){
        var service=service();r.setAttribute("groups",service.groups());var versions=service.versions(actor(r).id());r.setAttribute("versions",versions);
        if(!value(r,"id").isEmpty()){
            long id=fieldId(r,"id");var edit=versions.stream().filter(v->Sql.id(v.get("id"))==id).findFirst().orElseThrow(()->FieldValidationException.field("form","Không tìm thấy phiên bản."));
            r.setAttribute("edit",edit);r.setAttribute("items",service.items(actor(r).id(),id));
        }
        r.setAttribute("products",products().list(actor(r).id(),value(r,"q"),null,"",1));r.setAttribute("today",LocalDate.now(vn.codegym.salesinventory.config.VietnamTime.ZONE));
    }
    protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception{
        access(r).require("PRICE_READ");
        try{
            options(r);
            if(value(r,"action").equals("quote")){
                var q=service().quote(actor(r).id(),fieldId(r,"group"),fieldId(r,"product"),date(r,"date"));
                r.setAttribute("quote","Giá bán: "+q.sellingPrice()+"; giá sàn: "+q.floorPrice()+"; phiên bản: "+q.versionId());
            }
        }catch(IllegalArgumentException invalid){s.setStatus(400);r.setAttribute("errors",fieldErrors(invalid));}
        view(r,s,"pricing/prices");
    }
    protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception{
        access(r).require("PRICE_MANAGE");
        var form=formValues(r,"action","id","revision","name","group","from","to","parent","product","selling","floor","item");r.setAttribute("form",form);
        try{
            long id=value(r,"id").isEmpty()?0:fieldId(r,"id");var service=service();
            switch(value(r,"action")){
                case "create"->id=service.create(actor(r).id(),fieldId(r,"group"),value(r,"name"),date(r,"from"),date(r,"to"),value(r,"parent").isEmpty()?null:fieldId(r,"parent"));
                case "edit"->service.edit(actor(r).id(),id,fieldId(r,"revision"),value(r,"name"),date(r,"from"),date(r,"to"));
                case "item"->service.saveItem(actor(r).id(),id,fieldId(r,"product"),decimal(r,"selling"),decimal(r,"floor"),fieldId(r,"revision"));
                case "deleteItem"->service.deleteItem(actor(r).id(),id,fieldId(r,"item"),fieldId(r,"revision"));
                default->throw FieldValidationException.field("form","Thao tác không hợp lệ.");
            }
            redirect(r,s,"/pricing/lists?id="+id+"&notice=saved");
        }catch(IllegalArgumentException invalid){
            s.setStatus(400);r.setAttribute("errors",fieldErrors(invalid));
            try{options(r);}catch(IllegalArgumentException missing){r.setAttribute("groups",service().groups());r.setAttribute("versions",service().versions(actor(r).id()));r.setAttribute("products",products().list(actor(r).id(),"",null,"",1));r.setAttribute("today",LocalDate.now(vn.codegym.salesinventory.config.VietnamTime.ZONE));}
            view(r,s,"pricing/prices");
        }
    }
}

