package vn.codegym.salesinventory.service;

import vn.codegym.salesinventory.config.VietnamTime;
import vn.codegym.salesinventory.dao.Sql;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * S4-04: Tra cứu hàng đã mua và gợi ý nhóm hàng lần trước khi lập đơn cho đại lý được phân công.
 */
public final class PurchaseHistoryService {
    private final DataSource source;
    private final Clock clock;

    public PurchaseHistoryService(DataSource source) {
        this(source, Clock.systemUTC());
    }

    public PurchaseHistoryService(DataSource source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    // Chứa tổng lượng và bình quân mỗi đơn đã mua một SKU, theo đơn vị cơ sở.
    public record Item(
            long productId,
            String sku,
            String name,
            String unit,
            BigDecimal totalQuantity,
            long purchaseCount,
            BigDecimal averageQuantity) {}

    // Chứa dòng gợi ý từ đơn mua gần nhất; chỉ dùng để điền vào nháp, không lưu đơn.
    public record PreviousItem(
            long productId,
            String sku,
            String name,
            Long unitId,
            String unit,
            String quantity,
            boolean available,
            String notice) {}

    // Chứa kết quả tra cứu và chỉ xuất các trường cần dùng cho màn hình lập đơn.
    public record History(
            LocalDate from,
            LocalDate to,
            List<Item> items,
            Long lastOrderId,
            LocalDate lastPurchaseDate,
            List<PreviousItem> previousItems) {
        public History {
            items = List.copyOf(items);
            previousItems = List.copyOf(previousItems);
        }

        // Chuyển sang dữ liệu JSON công khai, không kèm giá vốn hoặc giá bán cũ.
        public Map<String, Object> display() {
            List<Map<String, Object>> summaries = new ArrayList<>();
            for (Item item : items) {
                summaries.add(
                        Map.of(
                                "productId",
                                item.productId,
                                "sku",
                                item.sku,
                                "name",
                                item.name,
                                "unit",
                                item.unit,
                                "totalQuantity",
                                item.totalQuantity.stripTrailingZeros().toPlainString(),
                                "purchaseCount",
                                item.purchaseCount,
                                "averageQuantity",
                                item.averageQuantity.stripTrailingZeros().toPlainString()));
            }
            List<Map<String, Object>> suggestions = new ArrayList<>();
            for (PreviousItem item : previousItems) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("productId", item.productId);
                row.put("sku", item.sku);
                row.put("name", item.name);
                row.put("unitId", item.unitId);
                row.put("unit", item.unit);
                row.put("quantity", item.quantity);
                row.put("available", item.available);
                row.put("notice", item.notice);
                suggestions.add(row);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("from", from.toString());
            result.put("to", to.toString());
            result.put("items", summaries);
            result.put("lastOrderId", lastOrderId);
            result.put(
                    "lastPurchaseDate",
                    lastPurchaseDate == null ? null : lastPurchaseDate.toString());
            result.put("previousItems", suggestions);
            return result;
        }
    }

    // Kiểm phân công hiện tại, rồi gom lượng đã xuất hóa đơn trong ba tháng theo giờ Việt Nam.
    public History load(long actor, long dealer) {
        if (dealer <= 0) {
            throw new IllegalArgumentException("Đại lý không hợp lệ.");
        }
        LocalDate today = LocalDate.now(clock.withZone(VietnamTime.ZONE));
        LocalDate from = today.minusMonths(3);
        return Sql.snapshot(
                source,
                connection -> {
                    var access = MvpAccess.internal(connection, actor, "ORDER_WRITE");
                    access.require("DEALER_READ");
                    // Khóa chia sẻ giữ phân công ổn định trong lúc đọc; không dùng quyền đọc toàn
                    // bộ để vượt phạm vi.
                    if (Sql.query(
                                    connection,
                                    "SELECT id FROM dealers WHERE id=? AND primary_staff_id=? FOR"
                                        + " SHARE",
                                    dealer,
                                    actor)
                            .isEmpty()) {
                        throw new SecurityException(
                                "Đại lý không thuộc phân công hiện tại của bạn.");
                    }
                    List<Item> items = new ArrayList<>();
                    for (var row :
                            Sql.query(
                                    connection,
                                    "SELECT p.id,p.sku,p.name,p.base_unit,SUM(il.quantity)"
                                        + " total_quantity,COUNT(DISTINCT s.order_id)"
                                        + " purchase_count FROM mvp_invoices i JOIN"
                                        + " mvp_invoice_lines il ON il.invoice_id=i.id JOIN"
                                        + " mvp_shipments s ON s.id=i.shipment_id JOIN products p"
                                        + " ON p.id=il.product_id WHERE i.dealer_id=? AND"
                                        + " i.invoice_date BETWEEN ? AND ? GROUP BY"
                                        + " p.id,p.sku,p.name,p.base_unit ORDER BY p.sku,p.id",
                                    dealer,
                                    from,
                                    today)) {
                        BigDecimal quantity = (BigDecimal) row.get("total_quantity");
                        long purchases = Sql.id(row.get("purchase_count"));
                        items.add(
                                new Item(
                                        Sql.id(row.get("id")),
                                        Sql.text(row.get("sku")),
                                        Sql.text(row.get("name")),
                                        Sql.text(row.get("base_unit")),
                                        quantity,
                                        purchases,
                                        quantity.divide(
                                                BigDecimal.valueOf(purchases),
                                                6,
                                                RoundingMode.HALF_UP)));
                    }
                    var latest =
                            Sql.query(
                                    connection,
                                    "SELECT s.order_id,i.invoice_date FROM mvp_invoices i JOIN"
                                        + " mvp_shipments s ON s.id=i.shipment_id WHERE"
                                        + " i.dealer_id=? AND i.invoice_date BETWEEN ? AND ? ORDER"
                                        + " BY i.invoice_date DESC,i.issued_at DESC,i.id DESC LIMIT"
                                        + " 1",
                                    dealer,
                                    from,
                                    today);
                    if (latest.isEmpty()) {
                        return new History(from, today, items, null, null, List.of());
                    }
                    long lastOrder = Sql.id(latest.get(0).get("order_id"));
                    List<PreviousItem> previous = new ArrayList<>();
                    for (var row :
                            Sql.query(
                                    connection,
                                    "SELECT p.id,p.sku,p.name,p.base_unit,p.status,u.id"
                                        + " unit_id,SUM(il.quantity) quantity FROM mvp_invoices i"
                                        + " JOIN mvp_shipments s ON s.id=i.shipment_id JOIN"
                                        + " mvp_invoice_lines il ON il.invoice_id=i.id JOIN"
                                        + " products p ON p.id=il.product_id LEFT JOIN"
                                        + " product_units u ON u.product_id=p.id AND u.is_base=1"
                                        + " AND u.factor=1 AND u.warehouse_id IS NULL WHERE"
                                        + " i.dealer_id=? AND s.order_id=? AND i.invoice_date"
                                        + " BETWEEN ? AND ? GROUP BY"
                                        + " p.id,p.sku,p.name,p.base_unit,p.status,u.id ORDER BY"
                                        + " p.sku,p.id",
                                    dealer,
                                    lastOrder,
                                    from,
                                    today)) {
                        boolean available =
                                "ACTIVE".equals(row.get("status")) && row.get("unit_id") != null;
                        BigDecimal quantity =
                                ((BigDecimal) row.get("quantity")).stripTrailingZeros();
                        String notice =
                                available
                                        ? ""
                                        : "Sản phẩm ngừng bán hoặc không còn đơn vị cơ sở hợp lệ.";
                        // Không làm tròn lượng cũ âm thầm: dùng 1 làm gợi ý và yêu cầu người lập
                        // đơn kiểm tra lại.
                        if (available
                                && (quantity.scale() > 6
                                        || quantity.precision() - quantity.scale() > 13)) {
                            quantity = BigDecimal.ONE;
                            notice =
                                    "Lượng mua cũ vượt giới hạn nhập của đơn; tạm điền 1, hãy kiểm"
                                        + " tra lại.";
                        }
                        previous.add(
                                new PreviousItem(
                                        Sql.id(row.get("id")),
                                        Sql.text(row.get("sku")),
                                        Sql.text(row.get("name")),
                                        row.get("unit_id") == null
                                                ? null
                                                : Sql.id(row.get("unit_id")),
                                        Sql.text(row.get("base_unit")),
                                        quantity.toPlainString(),
                                        available,
                                        notice));
                    }
                    return new History(
                            from,
                            today,
                            items,
                            lastOrder,
                            LocalDate.parse(latest.get(0).get("invoice_date").toString()),
                            previous);
                });
    }
}
