package vn.codegym.salesinventory.dto;

import java.util.List;
import vn.codegym.salesinventory.model.ManagedUser;

/**
 * S1-08: Chứa danh sách tài khoản của một trang và thông tin phân trang.
 */
public record UserPage(List<ManagedUser> items, long totalItems, int page, int pageSize) {

    public UserPage {
        items = List.copyOf(items);
    }

    public int totalPages() {
        return Math.max(1, (int) Math.ceil((double) totalItems / pageSize));
    }
}
