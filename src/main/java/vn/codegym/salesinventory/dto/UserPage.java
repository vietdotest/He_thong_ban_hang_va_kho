package vn.codegym.salesinventory.dto;

import java.util.List;
import vn.codegym.salesinventory.model.ManagedUser;

public record UserPage(List<ManagedUser> items, long totalItems, int page, int pageSize) {
    public UserPage {
        items = List.copyOf(items);
    }

    public int totalPages() {
        return Math.max(1, (int) Math.ceil((double) totalItems / pageSize));
    }
}
