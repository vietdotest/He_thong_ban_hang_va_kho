package vn.codegym.salesinventory.service;

import java.time.*;
import java.util.*;

/**
 * S2-01, S2-08: Chứa dữ liệu xem trước và lỗi từng dòng trước khi xác nhận nhập Excel.
 */
public final class ImportPreview {

    // Chứa dữ liệu, trạng thái và lỗi của một dòng xem trước.
    public record Line(int number, List<String> cells, String operation, String error) {

        public Line {
            cells = List.copyOf(cells);
        }

        public boolean isValid() {
            return error.isEmpty();
        }

        public int getNumber() {
            return number;
        }

        public List<String> getCells() {
            return cells;
        }

        public String getOperation() {
            return operation;
        }

        public String getError() {
            return error;
        }
    }

    // Chứa trạng thái sản phẩm dùng để đối chiếu khi nhập.
    public record ProductState(long id, long version) {}

    private final String token = UUID.randomUUID().toString();

    private final long owner;

    private final Instant expires;

    private final List<Line> lines;

    private boolean used;

    private final Map<Integer, ProductState> products;

    private final boolean costColumn;

    public ImportPreview(long owner, List<Line> lines, Instant now) {
        this(owner, lines, now, Map.of(), false);
    }

    public ImportPreview(
            long owner,
            List<Line> lines,
            Instant now,
            Map<Integer, ProductState> products,
            boolean costColumn) {
        this.owner = owner;
        this.lines = List.copyOf(lines);
        expires = now.plusSeconds(1800);
        this.products = Map.copyOf(products);
        this.costColumn = costColumn;
    }

    // Nhận công việc đang chờ để xử lý.
    public synchronized void claim(long actor, String supplied, Instant now) {
        if (owner != actor || !token.equals(supplied) || used || !now.isBefore(expires)) {
            throw new IllegalArgumentException(
                    "Bản xem trước đã hết hạn hoặc đã xác nhận. Hãy tải lại tệp.");
        }
        used = true;
    }

    public String getToken() {
        return token;
    }

    public List<Line> getLines() {
        return lines;
    }

    public ProductState productState(int row) {
        return products.get(row);
    }

    public boolean hasCostColumn() {
        return costColumn;
    }

    public synchronized boolean available(long actor, Instant now) {
        return owner == actor && !used && now.isBefore(expires);
    }
}
