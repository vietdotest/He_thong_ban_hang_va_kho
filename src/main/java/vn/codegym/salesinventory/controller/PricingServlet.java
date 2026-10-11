package vn.codegym.salesinventory.controller;
import jakarta.servlet.http.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.validation.*;
import vn.codegym.salesinventory.dto.PageRequest;
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
    private static Map<String,String> listContext(HttpServletRequest r){var params=new LinkedHashMap<String,String>();for(String key:List.of("q","versionQuery","itemQuery"))params.put(key,value(r,key));for(String prefix:List.of("version","item")){var page=PageRequest.parse(value(r,prefix+"Page"),value(r,prefix+"PageSize"));params.put(prefix+"Page",Integer.toString(page.page()));params.put(prefix+"PageSize",Integer.toString(page.pageSize()));}return params;}
    private static String returnPath(HttpServletRequest r,long id){return "/pricing/lists?"+(id>0?"id="+id+"&":"")+listContext(r).entrySet().stream().map(p->p.getKey()+"="+java.net.URLEncoder.encode(p.getValue(),java.nio.charset.StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));}
    private void baseOptions(HttpServletRequest r){
        var service=service();r.setAttribute("groups",service.groups());var versions=service.searchVersions(actor(r).id(),value(r,"versionQuery"),PageRequest.parse(value(r,"versionPage"),value(r,"versionPageSize")));r.setAttribute("versions",versions.items());r.setAttribute("versionPagination",versions);
        var parentOptions=new ArrayList<>(versions.items());if(value(r,"parent").matches("[1-9][0-9]{0,17}")){long parent=Long.parseLong(value(r,"parent"));if(parentOptions.stream().noneMatch(v->Sql.id(v.get("id"))==parent)){try{var selected=service.findVersion(actor(r).id(),parent);if(selected!=null)parentOptions.add(selected);}catch(IllegalArgumentException missing){parentOptions.add(Map.of("id",parent,"group_name","Không còn trong danh mục","name","Phiên bản #"+parent));}}}r.setAttribute("inheritanceVersions",parentOptions);
        var productOptions=new ArrayList<>(products().list(actor(r).id(),value(r,"q"),null,"",1));if(value(r,"product").matches("[1-9][0-9]{0,17}")){long product=Long.parseLong(value(r,"product"));if(productOptions.stream().noneMatch(p->Sql.id(p.get("id"))==product)){try{var selected=products().find(actor(r).id(),product);if(selected!=null)productOptions.add(selected);}catch(IllegalArgumentException missing){productOptions.add(Map.of("id",product,"sku",Long.toString(product),"name","Không còn trong danh mục"));}}}r.setAttribute("products",productOptions);
        r.setAttribute("priceContext",listContext(r));var pager=new LinkedHashMap<>(listContext(r));pager.remove("q");r.setAttribute("pricePagerContext",pager);r.setAttribute("priceReturn",returnPath(r,0));r.setAttribute("today",LocalDate.now(vn.codegym.salesinventory.config.VietnamTime.ZONE));
    }
    private void options(HttpServletRequest r){
        baseOptions(r);Map<String,Object> edit=null;
        if(!value(r,"id").isEmpty()){edit=service().findVersion(actor(r).id(),fieldId(r,"id"));if(edit==null)throw FieldValidationException.field("form","Không tìm thấy phiên bản.");}
        else if(!value(r,"new").equals("1")){var versions=(List<Map<String,Object>>)r.getAttribute("versions");if(!versions.isEmpty())edit=versions.get(0);}
        long id=edit==null?0:Sql.id(edit.get("id"));r.setAttribute("priceReturn",returnPath(r,id));var pager=new LinkedHashMap<>(listContext(r));pager.remove("q");if(id>0)pager.put("id",Long.toString(id));r.setAttribute("pricePagerContext",pager);
        if(edit!=null){r.setAttribute("edit",edit);var items=service().searchItems(actor(r).id(),id,value(r,"itemQuery"),PageRequest.parse(value(r,"itemPage"),value(r,"itemPageSize")));r.setAttribute("items",items.items());r.setAttribute("itemPagination",items);}
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
            redirect(r,s,returnPath(r,id)+"&notice=saved");
        }catch(IllegalArgumentException invalid){
            s.setStatus(400);r.setAttribute("errors",fieldErrors(invalid));
            try{options(r);}catch(IllegalArgumentException missing){baseOptions(r);}
            view(r,s,"pricing/prices");
        }
    }
}

