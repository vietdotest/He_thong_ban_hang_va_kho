package vn.codegym.salesinventory.validation;

/** Business state changed; retry must keep the original request key. */
public final class CommandConflictException extends IllegalArgumentException {
    public CommandConflictException(String message) { super(message); }
}
