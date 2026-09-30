package vn.codegym.salesinventory.security;

import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.Set;

public record CurrentUser(
        long id,
        String username,
        String email,
        String fullName,
        Set<String> roleCodes,
        boolean mustChangePassword
) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public CurrentUser {
        roleCodes = roleCodes == null ? Set.of() : Set.copyOf(roleCodes);
    }

    public CurrentUser(long id, String username, String email, String fullName) {
        this(id, username, email, fullName, Set.of(), false);
    }

    public boolean hasRole(String roleCode) {
        return roleCode != null && roleCodes.contains(roleCode.trim().toUpperCase(Locale.ROOT));
    }

    public CurrentUser passwordChangeCompleted() {
        return new CurrentUser(id, username, email, fullName, roleCodes, false);
    }
}
