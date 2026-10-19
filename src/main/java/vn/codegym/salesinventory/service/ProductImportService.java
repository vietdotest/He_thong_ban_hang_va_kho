package vn.codegym.salesinventory.service;

import java.util.*;
import java.time.*;
import javax.sql.DataSource;
import vn.codegym.salesinventory.dao.Sql;
import vn.codegym.salesinventory.security.Access;
import vn.codegym.salesinventory.validation.CatalogValidation;

/**
 * S2-08: Đọc, kiểm tra và nhập hoặc cập nhật sản phẩm từ Excel.
 */
public final class ProductImportService {

    public static final List<String> HEADERS =
            List.of("SKU", "Tên sản phẩm", "Mã nhóm", "Đơn vị cơ sở", "Quy cách", "Trạng thái");

    private final DataSource source;

    public ProductImportService(DataSource source) {
        this.source = source;
    }

    // Lấy tiêu đề các cột được phép xuất trong báo cáo nhập.
    public static List<String> headers(Access access) {
        var h = new ArrayList<>(HEADERS);
        if (access.allows("COST_WRITE")) {
            h.add("Giá vốn");
        }
        return h;
    }

    static String cell(List<String> row, int col) {
        return col < row.size() ? row.get(col).trim() : "";
    }

    private static String key(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    // Đọc dữ liệu biểu mẫu thành đầu vào cho danh mục sản phẩm từ Excel.
    static ProductService.Input input(List<String> row, long category, long version) {
        return new ProductService.Input(
                cell(row, 0),
                cell(row, 1),
                category,
                cell(row, 3),
                cell(row, 4),
                cell(row, 6).isEmpty() ? null : CatalogValidation.decimal(cell(row, 6), 4, false),
                null,
                cell(row, 5),
                version);
    }

    // Kiểm tra dữ liệu và tạo bản xem trước để người dùng xác nhận.
    public ImportPreview preview(long actor, byte[] bytes) {
        var access = new AccessService(source).load(actor);
        access.require("PRODUCT_MANAGE");
        var rows = Xlsx.read(bytes);
        if (rows.size() < 2) {
            throw new IllegalArgumentException("Tệp không có dòng dữ liệu.");
        }
        var header = rows.get(0).cells();
        if (header.contains("Giá vốn") && !access.allows("COST_WRITE")) {
            throw new SecurityException();
        }
        if (!rows.get(0).error().isEmpty() || !header.equals(headers(access))) {
            throw new IllegalArgumentException("Tiêu đề phải đúng tệp mẫu theo quyền của bạn.");
        }
        return Sql.transaction(
                source,
                connection -> {
                    Map<String, Long> categories = new HashMap<>();
                    for (var cat : Sql.query(connection, "SELECT id,code FROM categories")) {
                        categories.put(key(Sql.text(cat.get("code"))), Sql.id(cat.get("id")));
                    }
                    Map<String, ImportPreview.ProductState> existing = new HashMap<>();
                    for (var p : Sql.query(connection, "SELECT id,sku,version FROM products")) {
                        existing.put(
                                key(Sql.text(p.get("sku"))),
                                new ImportPreview.ProductState(
                                        Sql.id(p.get("id")), Sql.id(p.get("version"))));
                    }
                    Set<String> seen = new HashSet<>();
                    var lines = new ArrayList<ImportPreview.Line>();
                    var states = new HashMap<Integer, ImportPreview.ProductState>();
                    for (var row : rows.subList(1, rows.size())) {
                        if (row.cells().stream().allMatch(String::isBlank)) {
                            continue;
                        }
                        if (row.cells().size() > 6 && !access.allows("COST_WRITE")) {
                            throw new SecurityException();
                        }
                        String sku = key(cell(row.cells(), 0)), error = row.error();
                        var state =
                                existing.getOrDefault(sku, new ImportPreview.ProductState(0, 0));
                        try {
                            if (!error.isEmpty()) {
                                throw new IllegalArgumentException(error);
                            }
                            if (row.cells().size() > header.size()) {
                                throw new IllegalArgumentException("Dòng có cột ngoài tệp mẫu.");
                            }
                            Long category = categories.get(key(cell(row.cells(), 2)));
                            if (category == null) {
                                throw new IllegalArgumentException("Mã nhóm không tồn tại.");
                            }
                            ProductService.validate(input(row.cells(), category, state.version()));
                            if (!seen.add(sku)) {
                                throw new IllegalArgumentException("SKU trùng trong tệp.");
                            }
                        } catch (IllegalArgumentException invalid) {
                            error = invalid.getMessage();
                        }
                        states.put(row.number(), state);
                        lines.add(
                                new ImportPreview.Line(
                                        row.number(),
                                        row.cells(),
                                        state.id() != 0 ? "Cập nhật" : "Tạo mới",
                                        error));
                    }
                    if (lines.isEmpty()) {
                        throw new IllegalArgumentException("Tệp không có dòng dữ liệu.");
                    }
                    return new ImportPreview(
                            actor, lines, Instant.now(), states, header.contains("Giá vốn"));
                });
    }

    // Xác nhận dữ liệu xem trước và thực hiện thao tác đã chọn.
    public List<ImportPreview.Line> confirm(long actor, ImportPreview preview, String token) {
        var access = new AccessService(source).load(actor);
        access.require("PRODUCT_MANAGE");
        boolean hasCost = preview.hasCostColumn();
        if (hasCost) {
            access.require("COST_WRITE");
        }
        preview.claim(actor, token, Instant.now());
        var result = new ArrayList<ImportPreview.Line>();
        for (var row : preview.getLines()) {
            if (!row.isValid()) {
                result.add(row);
                continue;
            }
            var fresh = new AccessService(source).load(actor);
            fresh.require("PRODUCT_MANAGE");
            if (hasCost) {
                fresh.require("COST_WRITE");
            }
            String error = "";
            try {
                Sql.transaction(
                        source,
                        connection -> {
                            // Keep the same lock order as every other catalog write.
                            Sql.one(
                                    connection,
                                    "SELECT name FROM catalog_locks WHERE name='CATEGORY_TREE' FOR"
                                            + " UPDATE");
                            long category =
                                    Sql.id(
                                            Sql.one(
                                                            connection,
                                                            "SELECT id FROM categories WHERE"
                                                                    + " code=?",
                                                            cell(row.cells(), 2))
                                                    .get("id"));
                            var found =
                                    Sql.query(
                                            connection,
                                            "SELECT id,version FROM products WHERE sku=? FOR"
                                                    + " UPDATE",
                                            cell(row.cells(), 0));
                            var expected = preview.productState(row.number());
                            long id = found.isEmpty() ? 0 : Sql.id(found.get(0).get("id")),
                                    version =
                                            found.isEmpty()
                                                    ? 0
                                                    : Sql.id(found.get(0).get("version"));
                            if (expected == null
                                    || id != expected.id()
                                    || version != expected.version()) {
                                throw new IllegalArgumentException(
                                        "SKU đã thay đổi sau xem trước. Hãy tải lại tệp.");
                            }
                            ProductService.save(
                                    connection,
                                    fresh,
                                    actor,
                                    id,
                                    input(row.cells(), category, version));
                            return null;
                        });
            } catch (IllegalArgumentException invalid) {
                error = invalid.getMessage();
            }
            result.add(new ImportPreview.Line(row.number(), row.cells(), row.operation(), error));
        }
        var finalAccess = new AccessService(source).load(actor);
        finalAccess.require("PRODUCT_MANAGE");
        if (hasCost) {
            finalAccess.require("COST_READ");
        }
        return result;
    }
}
