package vn.codegym.salesinventory.model;

import java.time.Instant;

public record ManagedUser(
        long id,
        String username,
        String email,
        String fullName,
        String phone,
        UserStatus status,
        String roleCode,
        String roleName,
        boolean mustChangePassword,
        long version,
        Instant createdAt,
        String scopeSummary
) {
    public ManagedUser(long id,String username,String email,String fullName,String phone,UserStatus status,String roleCode,String roleName,boolean mustChangePassword,long version,Instant createdAt) {
        this(id,username,email,fullName,phone,status,roleCode,roleName,mustChangePassword,version,createdAt,"");
    }
}
