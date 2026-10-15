package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.service.*;
import vn.codegym.salesinventory.validation.CommandConflictException;

public final class StockReceiptServlet extends PortalServlet {
    private StockService service() { return new StockService(source()); }
    private static long optional(HttpServletRequest r,String name) { return value(r,name).isEmpty()?0:number(r,name); }
    private static String safeId(String raw) { try { return Long.parseLong(raw)>0?raw:""; } catch(Exception e) { return ""; } }
    private static String cell(String[] values,int index) { return values!=null&&index<values.length?values[index]:""; }
    @Override protected void get(HttpServletRequest r,HttpServletResponse s) throws Exception {
        long actor=actor(r).id(),id=optional(r,"id");
        r.setAttribute("receipts",service().receipts(actor));r.setAttribute("requestKey",UUID.randomUUID().toString());
        if(id>0) r.setAttribute("receipt",service().receipt(actor,id));
        if(!access(r).allows("STOCK_RECEIPT_WRITE")){if("new".equals(value(r,"action")))throw new SecurityException();view(r,s,"inventory/receipts");return;}
        r.setAttribute("warehouses",service().warehouses(actor,"STOCK_RECEIPT_WRITE"));
        Sql.snapshot(source(),c->{MvpAccess.internal(c,actor,"STOCK_RECEIPT_WRITE");
            r.setAttribute("suppliers",Sql.query(c,"SELECT s.id,s.code,s.name,s.warehouse_id FROM suppliers s WHERE s.status='ACTIVE' AND EXISTS(SELECT 1 FROM user_warehouses uw WHERE uw.user_id=? AND uw.warehouse_id=s.warehouse_id) ORDER BY s.name,s.id",actor));
            r.setAttribute("products",Sql.query(c,"SELECT id,sku,name FROM products WHERE status='ACTIVE' ORDER BY sku LIMIT 500"));
            r.setAttribute("units",Sql.query(c,"SELECT id,product_id,name,warehouse_id FROM product_units WHERE warehouse_id IS NULL OR EXISTS(SELECT 1 FROM user_warehouses uw WHERE uw.user_id=? AND uw.warehouse_id=product_units.warehouse_id) ORDER BY is_base DESC,name,id",actor));return null;});
        if(r.getAttribute("form")==null) {
            var row=id>0?service().receipt(actor,id):Map.<String,Object>of();var form=new LinkedHashMap<>(row);
            form.put("id",id);form.putIfAbsent("version",0);form.putIfAbsent("receipt_date",LocalDate.now(vn.codegym.salesinventory.config.VietnamTime.ZONE).toString());
            r.setAttribute("form",form);if(id>0) r.setAttribute("lineForms",row.get("lines"));
        }
        if(r.getAttribute("lineForms")==null) r.setAttribute("lineForms",List.of(Map.of("quantity","1")));
        view(r,s,"inventory/receipts");
    }
    @Override protected void post(HttpServletRequest r,HttpServletResponse s) throws Exception {
        access(r).require("STOCK_RECEIPT_WRITE");long id=optional(r,"id");
        try {
            switch(value(r,"action")) {
                case "save" -> {
                    String[] p=r.getParameterValues("product"),u=r.getParameterValues("unit"),q=r.getParameterValues("quantity"),lots=r.getParameterValues("lot"),expiry=r.getParameterValues("expiry");
                    if(p==null||p.length==0||p.length>100||u==null||q==null||lots==null||expiry==null||u.length!=p.length||q.length!=p.length||lots.length!=p.length||expiry.length!=p.length) throw new IllegalArgumentException("Dòng nhập không đầy đủ.");
                    var lines=new ArrayList<StockService.ReceiptLine>();
                    for(int i=0;i<p.length;i++) { if(q[i].length()>128) throw new IllegalArgumentException("Số lượng không hợp lệ.");lines.add(new StockService.ReceiptLine(Long.parseLong(p[i]),Long.parseLong(u[i]),new BigDecimal(q[i]),lots[i],expiry[i].isBlank()?null:LocalDate.parse(expiry[i]))); }
                    id=service().saveReceipt(actor(r).id(),id,optional(r,"version"),value(r,"requestKey"),new StockService.ReceiptInput(number(r,"warehouse"),number(r,"supplier"),value(r,"reference"),LocalDate.parse(value(r,"date")),lines));
                }
                case "confirm" -> service().confirmReceipt(actor(r).id(),id,number(r,"version"),value(r,"requestKey"));
                default -> throw new IllegalArgumentException("Thao tác không hợp lệ.");
            }
            redirect(r,s,"/inventory/receipts?id="+id+"&notice=saved");
        } catch(IllegalArgumentException|java.time.format.DateTimeParseException e) {
            s.setStatus(e instanceof CommandConflictException?409:400);r.setAttribute("errors",e instanceof IllegalArgumentException argument?fieldErrors(argument):Map.of("date","Ngày nhập hoặc hạn sử dụng không hợp lệ."));
            var f=new LinkedHashMap<String,Object>();f.put("id",id);f.put("version",value(r,"version"));f.put("warehouse_id",safeId(value(r,"warehouse")));f.put("supplier_id",safeId(value(r,"supplier")));f.put("document_ref",value(r,"reference"));f.put("receipt_date",value(r,"date"));r.setAttribute("form",f);
            String[] p=r.getParameterValues("product"),u=r.getParameterValues("unit"),q=r.getParameterValues("quantity"),l=r.getParameterValues("lot"),x=r.getParameterValues("expiry");
            if(p!=null) { var forms=new ArrayList<Map<String,Object>>();for(int i=0;i<Math.min(p.length,100);i++) forms.add(Map.of("product_id",safeId(p[i]),"unit_id",safeId(cell(u,i)),"quantity",cell(q,i),"lot_code",cell(l,i),"expires_on",cell(x,i)));r.setAttribute("lineForms",forms); }
            get(r,s);
        }
    }
}
