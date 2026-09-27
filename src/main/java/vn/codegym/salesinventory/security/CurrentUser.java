package vn.codegym.salesinventory.security;

import java.io.Serial;
import java.io.Serializable;

public record CurrentUser(long id, String username, String email, String fullName) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}
