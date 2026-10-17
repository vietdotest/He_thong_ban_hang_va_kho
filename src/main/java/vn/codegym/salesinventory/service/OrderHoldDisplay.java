package vn.codegym.salesinventory.service;

import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import vn.codegym.salesinventory.dao.Sql;

/** Read-only projection using the same remainder rules as reserve/renew. */
final class OrderHoldDisplay {
    private OrderHoldDisplay() { }
    static Map<String,Object> load(Connection c,long order,Instant now)throws SQLException {
        BigDecimal active=BigDecimal.ZERO,missing=BigDecimal.ZERO,protectedQuantity=BigDecimal.ZERO;
        Object nextExpiry=null,lastExpiry=null;
        for(var line:Sql.query(c,"SELECT id,base_quantity FROM order_lines WHERE order_id=?",order)) {
            long lineId=Sql.id(line.get("id"));BigDecimal held=BigDecimal.ZERO;
            for(var hold:Sql.query(c,"SELECT quantity,consumed,released,protected_at,expires_at FROM mvp_holds WHERE order_line_id=?",lineId)) {
                BigDecimal remainder=MvpCommands.decimal(hold.get("quantity")).subtract(MvpCommands.decimal(hold.get("consumed"))).subtract(MvpCommands.decimal(hold.get("released")));
                Object expiry=hold.get("expires_at");
                if(lastExpiry==null||Sql.instant(expiry).isAfter(Sql.instant(lastExpiry)))lastExpiry=expiry;
                if(remainder.signum()<=0)continue;
                if(hold.get("protected_at")!=null){held=held.add(remainder);protectedQuantity=protectedQuantity.add(remainder);}
                else if(Sql.instant(expiry).isAfter(now)){held=held.add(remainder);if(nextExpiry==null||Sql.instant(expiry).isBefore(Sql.instant(nextExpiry)))nextExpiry=expiry;}
            }
            BigDecimal shipped=CreditService.sum(c,"SELECT COALESCE(SUM(quantity-returned_quantity),0) amount FROM mvp_shipment_lines WHERE order_line_id=?",lineId);
            missing=missing.add(MvpCommands.decimal(line.get("base_quantity")).subtract(shipped).subtract(held).max(BigDecimal.ZERO));active=active.add(held);
        }
        String status=active.signum()>0?(missing.signum()>0?"Giữ hàng một phần":protectedQuantity.signum()>0?"Đang soạn — giữ hàng được bảo vệ":"Đang giữ hàng"):missing.signum()>0?(lastExpiry!=null?"Giữ hàng đã hết hạn / cần giữ lại":"Chưa giữ hàng"):"Đã xuất hết số lượng";
        var result=new LinkedHashMap<String,Object>();result.put("active",QuantityDisplay.format(active));result.put("missing",QuantityDisplay.format(missing));result.put("needsRenewal",missing.signum()>0);result.put("status",status);result.put("nextExpiry",TimeDisplay.format(nextExpiry));result.put("lastExpiry",TimeDisplay.format(lastExpiry));result.put("hasActive",active.signum()>0);return result;
    }
}
