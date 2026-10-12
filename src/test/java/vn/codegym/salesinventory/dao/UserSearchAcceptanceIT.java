package vn.codegym.salesinventory.dao;
import java.util.*;
import org.junit.jupiter.api.Test;
import vn.codegym.salesinventory.dto.UserSearchCriteria;
import static org.assertj.core.api.Assertions.*;
class UserSearchAcceptanceIT extends StoryDatabaseSupport {
    final JdbcUserManagementRepository users=new JdbcUserManagementRepository();
    @Test void internationalPhoneAndFiveDigitBoundaryUseTheSameCountFilter() {
        long target=user("SALES");String name="qa-phone-"+UUID.randomUUID().toString().replaceAll("[0-9-]","");
        String phone="0977"+String.format(Locale.ROOT,"%06d",target%1000000);
        update("UPDATE users SET username=?,username_normalized=?,phone=?,phone_normalized=?,status='PENDING_ACTIVATION' WHERE id=?",name,name,phone,phone,target);
        Sql.transaction(source,c->{
            for(String term:List.of("+84 "+phone.substring(1),phone.substring(0,5))) {
                var filter=new UserSearchCriteria(term,"SALES","PENDING_ACTIVATION",1);
                assertThat(users.search(c,filter)).extracting(u->u.id()).containsExactly(target);
                assertThat(users.count(c,filter)).isEqualTo(1);
            }
            for(String term:List.of("7",phone.substring(0,4))) {
                var filter=new UserSearchCriteria(term,"SALES","PENDING_ACTIVATION",1);
                assertThat(users.search(c,filter)).extracting(u->u.id()).doesNotContain(target);
            }
            return null;
        });
    }
    @Test void usernameContainingDigitsDoesNotMatchUnrelatedPhoneNumbers() {
        long target=user("SALES"), other=user("SALES");
        update("UPDATE users SET username='qa-search-name-01',username_normalized='qa-search-name-01' WHERE id=?",target);
        // Earlier suites may already own the common sample phone; reproduce that without reusing it.
        if(count("SELECT COUNT(*) n FROM users WHERE phone_normalized='0901234567'")==0) {
            long existing=user("SALES");
            update("UPDATE users SET phone='0901234567',phone_normalized='0901234567' WHERE id=?",existing);
        }
        long suffix=other%1000000;String phone;
        do { phone="0901"+String.format(Locale.ROOT,"%06d",suffix++%1000000); }
        while(count("SELECT COUNT(*) n FROM users WHERE phone_normalized=?",phone)>0);
        update("UPDATE users SET phone=?,phone_normalized=? WHERE id=?",phone,phone,other);
        var criteria=new UserSearchCriteria("qa-search-name-01","","",1);
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
