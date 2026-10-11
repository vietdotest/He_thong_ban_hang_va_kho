package vn.codegym.salesinventory.dto;

import java.util.Locale;

public record UserSearchCriteria(String keyword, String roleCode, String status, int page, int pageSize) {
    public static final int PAGE_SIZE = 20;

    public UserSearchCriteria {
        keyword = clean(keyword);
        roleCode = clean(roleCode).toUpperCase(Locale.ROOT);
        status = clean(status).toUpperCase(Locale.ROOT);
        var paging = new PageRequest(page, pageSize);
        page = paging.page();
        pageSize = paging.pageSize();
    }

    public UserSearchCriteria(String keyword, String roleCode, String status, int page) {
        this(keyword, roleCode, status, page, PAGE_SIZE);
    }

    public long offset() {
        return (page - 1L) * pageSize;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
