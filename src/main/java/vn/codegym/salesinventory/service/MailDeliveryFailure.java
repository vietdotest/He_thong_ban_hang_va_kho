package vn.codegym.salesinventory.service;

/** Safe classification only; never persist message bodies, passwords or activation URLs. */
public final class MailDeliveryFailure extends IllegalStateException {
    private final boolean definitive;
    public MailDeliveryFailure(boolean definitive,Throwable cause){super(definitive?"SMTP đã từ chối gửi email.":"Chưa xác định email đã được nhận; cần kiểm tra trước khi gửi lại.",cause);this.definitive=definitive;}
    public boolean definitive(){return definitive;}
    public static boolean isDefinitive(Throwable e){return e instanceof MailDeliveryFailure failure && failure.definitive;}
}
