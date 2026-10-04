package vn.codegym.salesinventory.security;

import java.util.*;

public record Access(Set<String> roles, Set<String> permissions, List<Map<String,Object>> roleNames,
                     List<Map<String,Object>> warehouses, List<Map<String,Object>> territories, String avatarKey) {
    public Access { roles=Set.copyOf(roles); permissions=Set.copyOf(permissions); roleNames=List.copyOf(roleNames); warehouses=List.copyOf(warehouses); territories=List.copyOf(territories); avatarKey=avatarKey==null?"":avatarKey; }
    public Access(Set<String> roles,Set<String> permissions,List<Map<String,Object>> roleNames,
                  List<Map<String,Object>> warehouses,List<Map<String,Object>> territories) {
        this(roles,permissions,roleNames,warehouses,territories,"");
    }
    public boolean allows(String permission) {
        if(permission.equals("COST_READ") || permission.equals("COST_WRITE"))
            return roles.contains("SALES_MANAGER") && permissions.contains(permission);
        return permissions.contains(permission);
    }
    public void require(String permission) { if(!allows(permission)) throw new SecurityException("Bạn không có quyền thực hiện thao tác này."); }
    public boolean managesWarehouse(long id) { return warehouses.stream().anyMatch(w -> ((Number)w.get("id")).longValue()==id); }
    public boolean warehouseScoped() { return roles.contains("WAREHOUSE")||roles.contains("WAREHOUSE_MANAGER")||allows("WAREHOUSE_MANAGE"); }
}
