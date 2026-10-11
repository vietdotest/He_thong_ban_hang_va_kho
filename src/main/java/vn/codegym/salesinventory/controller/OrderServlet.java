package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.dto.PageRequest;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.FieldValidationException;

public class OrderServlet extends PortalServlet {
    private final OrderService configured;private final DealerService dealers;private final ProductService products;
    public OrderServlet(){this(null,null,null);}
    OrderServlet(OrderService service,DealerService dealers,ProductService products){this.configured=service;this.dealers=dealers;this.products=products;}
    private OrderService service(){return configured==null?new OrderService(source()):configured;}
    private DealerService dealers(){return dealers==null?new DealerService(source()):dealers;}
    private ProductService products(){return products==null?new ProductService(source()):products;}
    protected boolean portalMode(){return false;}
    private String path(){return portalMode()?"/portal/orders":"/orders";}
    private String permission(boolean write){return portalMode()?(write?"PORTAL_ORDER_WRITE":"PORTAL_ORDER_READ"):(write?"ORDER_WRITE":"ORDER_READ");}
    private void layout(HttpServletRequest r){r.setAttribute("portalMode",portalMode());r.setAttribute("orderPath",path());r.setAttribute("optionsPath",portalMode()?"/api/portal/options":"/api/orders/options");}
    private static String filters(HttpServletRequest r){var result=new StringBuilder();for(String key:List.of("q","status","page","pageSize","filterDealer"))if(!value(r,key).isEmpty())result.append('&').append(key.equals("filterDealer")?"dealer":key).append('=').append(java.net.URLEncoder.encode(value(r,key),java.nio.charset.StandardCharsets.UTF_8));return result.toString();}
    private static long optional(HttpServletRequest r,String key){return value(r,key).isEmpty()?0:number(r,key);}
    private static List<Map<String,Object>> lineValues(HttpServletRequest r){var rows=new ArrayList<Map<String,Object>>();String[] products=r.getParameterValues("product"),units=r.getParameterValues("unit"),quantities=r.getParameterValues("quantity");
        if(products==null)return rows;if(products.length>100)throw FieldValidationException.field("lines","Tối đa 100 dòng hàng.");
        for(int i=0;i<products.length;i++)rows.add(Map.of("product_id",products[i],"unit_id",units!=null&&i<units.length?units[i]:"","quantity",quantities!=null&&i<quantities.length?quantities[i]:""));return rows;}
    private static OrderService.Input input(HttpServletRequest r){var values=lineValues(r);String[] units=r.getParameterValues("unit"),quantities=r.getParameterValues("quantity");if(units==null||quantities==null||units.length!=values.size()||quantities.length!=values.size())throw FieldValidationException.field("lines","Số trường sản phẩm / đơn vị / số lượng không khớp.");var lines=new ArrayList<OrderService.LineInput>();var errors=new LinkedHashMap<String,String>();for(int i=0;i<values.size();i++){var row=values.get(i);try{if(row.get("quantity").toString().length()>128)throw new NumberFormatException();lines.add(new OrderService.LineInput(Long.parseLong(row.get("product_id").toString()),Long.parseLong(row.get("unit_id").toString()),new BigDecimal(row.get("quantity").toString())));}catch(IllegalArgumentException e){errors.put("line"+i,"Chọn sản phẩm, đơn vị và số lượng hợp lệ.");}}
        if(!errors.isEmpty())throw new FieldValidationException(errors);LocalDate delivery;try{delivery=LocalDate.parse(value(r,"delivery"));}catch(Exception e){delivery=null;}
        return new OrderService.Input(optional(r,"dealer"),optional(r,"address"),delivery,optional(r,"version"),value(r,"creationKey"),lines);}
    private static void json(HttpServletResponse s,Map<String,Object> values)throws Exception{s.setContentType("application/json;charset=UTF-8");s.setHeader("Cache-Control","no-store");s.getWriter().write(AuditService.snapshot(values,false));}
    private void editor(HttpServletRequest r,long id,boolean quote)throws Exception{layout(r);long actor=actor(r).id();Map<String,Object> order=id==0?Map.of():service().find(actor,id);r.setAttribute("order",order);Map<String,Object> portalDealer=portalMode()?new PortalAccountService(source(),null).profile(actor):Map.of();if(portalMode()&&!value(r,"dealer").isEmpty()&&number(r,"dealer")!=Sql.id(portalDealer.get("id")))throw new SecurityException();
        if(r.getAttribute("form")==null){var form=new LinkedHashMap<String,Object>();form.put("id",id);form.put("dealer",id==0?(portalMode()?portalDealer.get("id"):value(r,"dealer")):order.get("dealer_id"));form.put("address",id==0?"":order.get("address_id"));form.put("delivery",id==0?LocalDate.now(vn.codegym.salesinventory.config.VietnamTime.ZONE).plusDays(1):order.get("desired_delivery"));form.put("version",id==0?0:order.get("version"));form.put("creationKey",id==0?UUID.randomUUID().toString():portalMode()?"":order.get("creation_key"));r.setAttribute("form",form);}
        @SuppressWarnings("unchecked")var form=(Map<String,Object>)r.getAttribute("form");long dealer=0;try{dealer=Long.parseLong(Sql.text(form.get("dealer")));}catch(NumberFormatException ignored){}
        if(dealer>0){r.setAttribute("selectedDealer",portalMode()?portalDealer:dealers().find(actor,dealer));r.setAttribute("addresses",service().addresses(actor,dealer));}
        if(r.getAttribute("lineForms")==null)r.setAttribute("lineForms",id==0?List.of(Map.of("product_id","","unit_id","","quantity","1")):order.get("lines"));
        r.setAttribute("products",portalMode()?new PortalCatalogService(source()).search(actor,"",new PageRequest(1,20)).items():products().search(actor,"",null,"ACTIVE",new PageRequest(1,20)).items());
        r.setAttribute("submitKey",UUID.randomUUID().toString());r.setAttribute("editing",id==0||Boolean.TRUE.equals(order.get("can_edit")));
        if(quote&&Boolean.TRUE.equals(order.get("can_edit")))r.setAttribute("quote",service().quote(actor,id).display());
        else if(quote&&"DRAFT".equals(order.get("status")))r.setAttribute("readQuote",service().estimate(actor,id).display());
    }
    protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception{layout(r);if(portalMode())new PortalAccountService(source(),null).profile(actor(r).id());if("/api/orders/options".equals(r.getServletPath())||"/api/portal/options".equals(r.getServletPath())){long dealer=number(r,"dealer");if(value(r,"product").isEmpty())json(s,Map.of("items",service().addresses(actor(r).id(),dealer)));else json(s,Map.of("items",service().units(actor(r).id(),dealer,number(r,"product"))));return;}
        if("/api/portal/products".equals(r.getServletPath())){json(s,Map.of("items",new PortalCatalogService(source()).suggest(actor(r).id(),value(r,"q"))));return;}
        if("/portal".equals(r.getServletPath())){r.setAttribute("dealer",new PortalAccountService(source(),null).profile(actor(r).id()));var offers=new PortalCatalogService(source()).search(actor(r).id(),value(r,"q"),PageRequest.parse(value(r,"page"),value(r,"pageSize")));r.setAttribute("products",offers.items());r.setAttribute("pagination",offers);view(r,s,"portal/home");return;}
        long id=optional(r,"id");if(id>0||"new".equals(value(r,"action"))){access(r).require(permission(id==0));editor(r,id,true);view(r,s,"orders/editor");return;}
        Long dealer=optional(r,"dealer")==0?null:optional(r,"dealer");var p=service().search(actor(r).id(),value(r,"q"),value(r,"status"),dealer,PageRequest.parse(value(r,"page"),value(r,"pageSize")));r.setAttribute("orders",p.items());r.setAttribute("pagination",p);view(r,s,"orders/list");}
    protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception{layout(r);access(r).require(permission(true));String action=value(r,"action");long id=optional(r,"id");
        if("preview".equals(action)){try{json(s,service().preview(actor(r).id(),input(r)).display());}catch(IllegalArgumentException e){s.setStatus(400);json(s,Map.of("errors",fieldErrors(e)));}return;}
        try{switch(action){case "save"->{long saved=service().save(actor(r).id(),id,input(r));redirect(r,s,path()+"?id="+saved+"&notice=saved"+filters(r));}
                case "takeover"->{if(portalMode())throw new SecurityException();service().takeover(actor(r).id(),id,number(r,"version"),value(r,"reason"));redirect(r,s,path()+"?id="+id+"&notice=taken-over"+filters(r));}
                case "submit"->{var result=service().submit(actor(r).id(),id,number(r,"version"),value(r,"quoteToken"),value(r,"submitKey"),"true".equals(value(r,"confirmed")));if(result.submitted())redirect(r,s,path()+"?id="+id+"&notice=submitted"+filters(r));else{editor(r,id,false);r.setAttribute("quote",result.quote().display());view(r,s,"orders/editor");}}
                default->s.sendError(400);}}
        catch(IllegalArgumentException e){s.setStatus(400);r.setAttribute("errors",fieldErrors(e));if("save".equals(action)){r.setAttribute("form",formValues(r,"id","dealer","address","delivery","version","creationKey"));r.setAttribute("lineForms",lineValues(r));r.setAttribute("unsaved",true);}editor(r,id,false);view(r,s,"orders/editor");}
    }
}
