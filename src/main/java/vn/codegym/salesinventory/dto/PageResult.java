package vn.codegym.salesinventory.dto;

import java.util.List;

public record PageResult<T>(List<T> items, long totalItems, int page, int pageSize) {
    public PageResult { items = List.copyOf(items); }
    public long getTotalPages() { return Math.max(1, totalItems / pageSize + (totalItems % pageSize == 0 ? 0 : 1)); }
    public List<T> getItems() { return items; }
    public long getTotalItems() { return totalItems; }
    public int getPage() { return page; }
    public int getPageSize() { return pageSize; }
    public long getFirstItem() { return totalItems == 0 ? 0 : (page - 1L) * pageSize + 1; }
    public long getLastItem() { return Math.min(totalItems, (page - 1L) * pageSize + items.size()); }
    public long getWindowStart() { return Math.max(1, Math.min(page - 2L, getTotalPages() - 4)); }
    public long getWindowEnd() { return Math.min(getTotalPages(), getWindowStart() + 4); }
}
