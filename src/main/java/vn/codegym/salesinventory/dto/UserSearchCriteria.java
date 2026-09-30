package vn.codegym.salesinventory.dto;

import java.util.Locale;

public record UserSearchCriteria(String keyword, String roleCode, String status, int page) {
    public static final int PAGE_SIZE = 20;

    public UserSearchCriteria {
        keyword = clean(keyword);
        roleCode = clean(roleCode).toUpperCase(Locale.ROOT);
        status = clean(status).toUpperCase(Locale.ROOT);
        page = Math.max(page, 1);
    }

    public int offset() {
        return (page - 1) * PAGE_SIZE;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
