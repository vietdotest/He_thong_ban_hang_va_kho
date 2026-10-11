package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import vn.codegym.salesinventory.dto.PageRequest;
import vn.codegym.salesinventory.service.DealerService;

public final class DealerServlet extends PortalServlet {
    private DealerService service(){return new DealerService(source());}
    private static String filterValue(HttpServletRequest r,String key){return value(r,"POST".equals(r.getMethod())?"filter"+Character.toUpperCase(key.charAt(0))+key.substring(1):key);}
    private static Long filterId(HttpServletRequest r,String key){String v=filterValue(r,key);if(v.isEmpty())return null;try{return Long.valueOf(v);}catch(NumberFormatException e){throw new IllegalArgumentException("Bộ lọc không hợp lệ.");}}
    private void list(HttpServletRequest r){
        var result=service().search(actor(r).id(),new DealerService.Filter(value(r,"q"),filterId(r,"territory"),filterId(r,"group"),filterId(r,"staff"),filterValue(r,"status")),PageRequest.parse(value(r,"page"),value(r,"pageSize")));
        r.setAttribute("dealers",result.items());r.setAttribute("pagination",result);
        var selected=new java.util.LinkedHashMap<String,String>();selected.put("q",value(r,"q"));for(String key:new String[]{"group","territory","staff","status"})selected.put(key,filterValue(r,key));r.setAttribute("dealerFilter",selected);r.setAttribute("paginationFilterValues",selected);
        service().filterOptions(actor(r).id()).forEach(r::setAttribute);
        if(access(r).allows("DEALER_MANAGE"))service().options(actor(r).id()).forEach(r::setAttribute);
    }
    protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception{
        list(r);if(!value(r,"id").isEmpty())r.setAttribute("edit",service().find(actor(r).id(),number(r,"id")));
        view(r,s,"dealers/list");
    }
    protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception{
        access(r).require("DEALER_MANAGE");
        var form=formValues(r,"id","version","code","name","taxCode","phone","group","territory","staff","warehouse","status");
        for(var pair:new String[][]{{"taxCode","tax_code"},{"group","group_id"},{"territory","territory_id"},{"staff","primary_staff_id"},{"warehouse","warehouse_id"}})form.put(pair[1],form.remove(pair[0]));
        try {
            long id=value(r,"id").isEmpty()?0:number(r,"id");long version=value(r,"version").isEmpty()?0:number(r,"version");
            if("delete".equals(value(r,"action")))service().delete(actor(r).id(),id,version);
            else service().save(actor(r).id(),id,new DealerService.Input(value(r,"code"),value(r,"name"),value(r,"taxCode"),value(r,"phone"),number(r,"group"),number(r,"territory"),number(r,"staff"),value(r,"warehouse").isEmpty()?null:number(r,"warehouse"),value(r,"status"),version));
            String filters="";for(String key:new String[]{"group","territory","staff","status"})filters+="&"+key+"="+URLEncoder.encode(filterValue(r,key),StandardCharsets.UTF_8);
            redirect(r,s,"/dealers?notice=saved&q="+URLEncoder.encode(value(r,"q"),StandardCharsets.UTF_8)+"&page="+PageRequest.parse(value(r,"page"),value(r,"pageSize")).page()+"&pageSize="+PageRequest.parse(value(r,"page"),value(r,"pageSize")).pageSize()+filters);
        }catch(IllegalArgumentException invalid){s.setStatus(400);r.setAttribute("edit",form);r.setAttribute("errors",fieldErrors(invalid));list(r);view(r,s,"dealers/list");}
    }
}
