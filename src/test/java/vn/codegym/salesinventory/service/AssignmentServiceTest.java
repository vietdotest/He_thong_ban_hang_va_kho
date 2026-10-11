package vn.codegym.salesinventory.service;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class AssignmentServiceTest {
    @Test void warehouseRoleRequiresAnAssignedWarehouse() { assertThatThrownBy(() -> AssignmentService.validate(1,2,Set.of("WAREHOUSE"),Set.of())).isInstanceOf(IllegalArgumentException.class); }
    @Test void administratorCannotRemoveOwnRoleEvenWhenAddingAnotherRole() { assertThatThrownBy(() -> AssignmentService.validate(1,1,Set.of("SALES_MANAGER"),Set.of())).isInstanceOf(IllegalArgumentException.class); }
    @Test void supportsSeveralRolesAndSeveralWarehouses() { assertThatCode(() -> AssignmentService.validate(1,2,Set.of("WAREHOUSE","SALES"),Set.of(10L,20L))).doesNotThrowAnyException(); }
}
