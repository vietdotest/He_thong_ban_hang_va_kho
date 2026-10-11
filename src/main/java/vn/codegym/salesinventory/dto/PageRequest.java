package vn.codegym.salesinventory.dto;

/** Bounded paging shared by database-backed lists. */
public record PageRequest(int page, int pageSize) {
    public PageRequest {
        page = Math.max(1, page);
        pageSize = pageSize == 50 || pageSize == 100 ? pageSize : 20;
    }
    public static PageRequest parse(String page, String size) {
        return new PageRequest(parseInt(page, 1), parseInt(size, 20));
    }
    private static int parseInt(String raw, int fallback) {
        try { return Integer.parseInt(raw); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    public long offset() { return (page - 1L) * pageSize; }
    public PageRequest clamp(long total) {
        long last = Math.max(1, total / pageSize + (total % pageSize == 0 ? 0 : 1));
        return new PageRequest((int)Math.min(page, last), pageSize);
    }
}
