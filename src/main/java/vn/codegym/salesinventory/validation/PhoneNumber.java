package vn.codegym.salesinventory.validation;
public final class PhoneNumber {
    private PhoneNumber() { }
    public static String normalize(String phone) {
        String n=phone==null ? "" : phone.trim().replaceAll("[\\s().-]","");
        if(n.startsWith("+84")) n="0"+n.substring(3);
        if(!n.matches("(?:0[35789][0-9]{8}|02[0-9]{9})")) throw new IllegalArgumentException("Số điện thoại Việt Nam không đúng định dạng.");
        return n;
    }
}
