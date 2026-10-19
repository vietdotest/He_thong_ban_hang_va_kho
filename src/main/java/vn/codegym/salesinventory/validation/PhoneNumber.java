package vn.codegym.salesinventory.validation;

/**
 * S1-08, S2-02, S3-03: Chuẩn hóa và kiểm tra số điện thoại trước khi lưu.
 */
public final class PhoneNumber {

    private PhoneNumber() {}

    // Chuẩn hóa chữ số của từ khóa dùng để tìm theo số điện thoại.
    public static String searchDigits(String phone) {
        String n = phone == null ? "" : phone.trim();
        if (!n.matches("[+0-9() .\\s-]+")) {
            return "";
        }
        n = n.replaceAll("[\\s().-]", "");
        if (n.startsWith("+84")) {
            n = "0" + n.substring(3);
        }
        return n.matches("[0-9]{5,}") ? n : "";
    }

    // Chuẩn hóa dữ liệu đầu vào trước khi kiểm tra hoặc lưu.
    public static String normalize(String phone) {
        String n = phone == null ? "" : phone.trim().replaceAll("[\\s().-]", "");
        if (n.startsWith("+84")) {
            n = "0" + n.substring(3);
        }
        if (!n.matches("(?:0[35789][0-9]{8}|02[0-9]{9})")) {
            throw new IllegalArgumentException("Số điện thoại Việt Nam không đúng định dạng.");
        }
        return n;
    }
}
