package vn.codegym.salesinventory.dao;
import java.util.*;
import org.junit.jupiter.api.Test;
import vn.codegym.salesinventory.dto.UserSearchCriteria;
import static org.assertj.core.api.Assertions.*;
class UserSearchAcceptanceIT extends StoryDatabaseSupport {
    final JdbcUserManagementRepository users=new JdbcUserManagementRepository();
    @Test void usernameContainingDigitsDoesNotMatchUnrelatedPhoneNumbers() {
        long target=user("SALES"), other=user("SALES");
        update("UPDATE users SET username='qa-claude-01',username_normalized='qa-claude-01' WHERE id=?",target);
        update("UPDATE users SET phone='0901234567',phone_normalized='0901234567' WHERE id=?",other);
        var criteria=new UserSearchCriteria("qa-claude-01","","",1);
        Sql.transaction(source,c->{assertThat(users.search(c,criteria)).extracting(u->u.id()).containsExactly(target);assertThat(users.count(c,criteria)).isEqualTo(1);return null;});
    }
    @Test void formattedPhoneSearchAndPendingFilterHaveMatchingCountAndPages() {
        long target=user("SALES"), other=user("SALES");
        update("UPDATE users SET phone='0987654321',phone_normalized='0987654321',status='PENDING_ACTIVATION' WHERE id=?",target);
        update("UPDATE users SET phone='0987654322',phone_normalized='0987654322' WHERE id=?",other);
        var criteria=new UserSearchCriteria("098-765","SALES","PENDING_ACTIVATION",1);
        Sql.transaction(source,c->{assertThat(users.search(c,criteria)).extracting(u->u.id()).containsExactly(target);assertThat(users.count(c,criteria)).isEqualTo(1);return null;});
        var active=new UserSearchCriteria("098 765","SALES","ACTIVE",1);
        Sql.transaction(source,c->{assertThat(users.search(c,active)).extracting(u->u.id()).containsExactly(other);assertThat(users.count(c,active)).isEqualTo(1);return null;});
    }
}
