package vn.codegym.salesinventory.config;

import java.time.ZoneId;

/**
 * Dùng chung: Quy định múi giờ Việt Nam để lưu, đọc và hiển thị thời gian thống nhất.
 */
public final class VietnamTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public static final String OFFSET = "+07:00";

    private VietnamTime() {}
}
