package vn.codegym.salesinventory.controller;

import jakarta.servlet.http.*;
import java.math.BigDecimal;
import java.util.*;
import vn.codegym.salesinventory.service.FulfillmentService;
import vn.codegym.salesinventory.validation.CommandConflictException;

public final class FulfillmentServlet extends PortalServlet {
    private FulfillmentService service(){return new FulfillmentService(source());}
    private void load(HttpServletRequest r,long order){
        r.setAttribute("orders",service().orders(actor(r).id()));
        r.setAttribute("shipments",service().shipments(actor(r).id()));
        if(order>0)r.setAttribute("order",service().order(actor(r).id(),order));
        r.setAttribute("requestKey",UUID.randomUUID().toString());
    }
    @Override protected void get(HttpServletRequest r,HttpServletResponse s)throws Exception {
        if(!value(r,"id").isEmpty())r.setAttribute("shipment",service().shipment(actor(r).id(),number(r,"id")));
        load(r,value(r,"order").isEmpty()?0:number(r,"order"));view(r,s,"fulfillment/shipments");
    }
    @Override protected void post(HttpServletRequest r,HttpServletResponse s)throws Exception {
        access(r).require("FULFILLMENT_WRITE");
        if("deliver".equals(value(r,"action"))){long id=number(r,"id");
            try{String[] ids=r.getParameterValues("shipmentLine"),quantities=r.getParameterValues("received");if(ids==null||quantities==null||ids.length!=quantities.length)throw new IllegalArgumentException("Dòng giao không hợp lệ.");
                var lines=new ArrayList<FulfillmentService.DeliveryLine>();var values=new LinkedHashMap<String,String>();for(int i=0;i<ids.length;i++)values.put(ids[i],quantities[i]);r.setAttribute("receivedValues",values);r.setAttribute("recipient",value(r,"recipient"));r.setAttribute("deliveryReason",value(r,"reason"));
                for(int i=0;i<ids.length;i++){if(quantities[i].length()>128)throw new IllegalArgumentException("Số lượng không hợp lệ.");lines.add(new FulfillmentService.DeliveryLine(Long.parseLong(ids[i]),new BigDecimal(quantities[i])));}
                service().deliver(actor(r).id(),id,number(r,"version"),value(r,"requestKey"),value(r,"recipient"),lines,value(r,"reason"));redirect(r,s,"/fulfillment/shipments?id="+id+"&notice=saved");
            }catch(IllegalArgumentException e){s.setStatus(e instanceof CommandConflictException?409:400);r.setAttribute("errors",fieldErrors(e));r.setAttribute("shipment",service().shipment(actor(r).id(),id));load(r,0);view(r,s,"fulfillment/shipments");}return;
        }
        long order=number(r,"order");
        try{
            switch(value(r,"action")) {
                case "pick"->service().startPicking(actor(r).id(),order,number(r,"version"),value(r,"requestKey"));
                case "dispatch"->{
                    String[] ids=r.getParameterValues("line"),quantities=r.getParameterValues("quantity");
                    if(ids==null||quantities==null||ids.length!=quantities.length||ids.length>100)throw new IllegalArgumentException("Dòng xuất không hợp lệ.");
                    var lines=new ArrayList<FulfillmentService.DispatchLine>();var values=new LinkedHashMap<String,String>();
                    for(int i=0;i<ids.length;i++)values.put(ids[i],quantities[i]);r.setAttribute("lineValues",values);r.setAttribute("reason",value(r,"reason"));
                    for(int i=0;i<ids.length;i++){if(quantities[i].length()>128)throw new IllegalArgumentException("Số lượng không hợp lệ.");BigDecimal quantity=new BigDecimal(quantities[i]);if(quantity.signum()!=0)lines.add(new FulfillmentService.DispatchLine(Long.parseLong(ids[i]),quantity));}
                    service().dispatch(actor(r).id(),order,number(r,"version"),value(r,"requestKey"),lines,value(r,"reason"));
                }
                default->{s.sendError(400);return;}
            }
            redirect(r,s,"/fulfillment/shipments?order="+order+"&notice=saved");
        }catch(IllegalArgumentException e){s.setStatus(e instanceof CommandConflictException?409:400);r.setAttribute("errors",fieldErrors(e));load(r,order);view(r,s,"fulfillment/shipments");}
    }
}
