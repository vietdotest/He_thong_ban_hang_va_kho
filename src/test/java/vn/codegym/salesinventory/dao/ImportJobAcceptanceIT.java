package vn.codegym.salesinventory.dao;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import vn.codegym.salesinventory.dto.*;
import vn.codegym.salesinventory.security.*;
import vn.codegym.salesinventory.service.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses a disposable MySQL and an explicitly mocked hasher/mail, not a throughput benchmark. */
class ImportJobAcceptanceIT extends StoryDatabaseSupport {
    static final AtomicInteger numbers=new AtomicInteger(35000000);
    long admin,manager,category;Clock clock;MailService mail;UserManagementService management;ImportJobService jobs;
    @BeforeEach void fixture(){
        admin=user("ADMIN");manager=user("SALES_MANAGER");category=category(manager);clock=Clock.systemUTC();mail=mock(MailService.class);
        PasswordHasher hasher=mock(PasswordHasher.class);when(hasher.hash(anyString())).thenReturn("qa-injected-test-hash");
        management=new UserManagementService(source,new JdbcUserManagementRepository(),new JdbcSessionRepository(),new JdbcAuditLogRepository(),hasher,mail,new TemporaryPasswordGenerator(),clock,new ActivationService(source,clock,"http://qa.local",mail));
        jobs=new ImportJobService(source,clock,Duration.ofMinutes(30),Duration.ofDays(7));
    }
    @AfterEach void finishFixtures(){update("DROP TRIGGER IF EXISTS import_outcome_fail");update("UPDATE import_jobs SET status='STOPPED',lease_owner=NULL,lease_until=NULL WHERE actor_id IN (?,?) AND status IN ('QUEUED','RUNNING')",admin,manager);}
    List<String> account(){int n=numbers.getAndIncrement();return List.of("imp-"+n,"imp-"+n+"@qa.local","Nguyễn QA "+n,"09"+n,"SALES","","");}
    List<String> productRow(int n){return List.of("I-"+UUID.randomUUID(),"Sản phẩm "+n,Sql.text(one("SELECT code FROM categories WHERE id=?",category).get("code")),"Cái","Thùng 12","ACTIVE","123.4567");}
    byte[] book(List<String> headers,List<List<String>> rows){var all=new ArrayList<List<String>>();all.add(headers);all.addAll(rows);return Xlsx.write(all);}
    String users(List<List<String>> rows){var preview=new UserImportService(source,management).preview(admin,book(UserImportService.HEADERS,rows));return jobs.create(admin,"USER","nguoi-dung.xlsx",preview);}
    String products(List<List<String>> rows){var preview=new ProductImportService(source).preview(manager,book(ProductImportService.headers(new AccessService(source).load(manager)),rows));return jobs.create(manager,"PRODUCT","san-pham.xlsx",preview);}
    void confirm(String id,long actor,String kind){var j=jobs.find(actor,kind,id);jobs.confirm(actor,kind,id,Sql.id(j.get("version")),Sql.text(j.get("confirm_key")));}
    ImportWorker worker(){return new ImportWorker(source,management,1,10,clock,Duration.ofDays(7));}
    String state(String id,int row){return Sql.text(one("SELECT state FROM import_job_rows WHERE job_id=? AND source_row=?",id,row).get("state"));}

    @Test void revokedPermissionBetweenRowsPreservesCommittedOutcomeAndStopsRemaining(){
        var input=List.of(account(),account(),account());String id=users(input);confirm(id,admin,"USER");
        ImportWorker first=worker();doAnswer(call->{first.close();return null;}).when(mail).sendActivation(anyString(),anyString(),anyString(),anyString(),anyString());first.runOnce();
        assertThat(state(id,2)).isEqualTo("SUCCESS");update("DELETE FROM user_roles WHERE user_id=?",admin);
        try(var second=worker()){second.runOnce();}
        assertThat(state(id,2)).isEqualTo("SUCCESS");assertThat(state(id,3)).isEqualTo("SKIPPED");assertThat(state(id,4)).isEqualTo("SKIPPED");
        assertThat(one("SELECT status FROM import_jobs WHERE id=?",id).get("status")).isEqualTo("STOPPED");
        verify(mail,times(1)).sendActivation(anyString(),anyString(),anyString(),anyString(),anyString());
        assertThat(count("SELECT COUNT(*) n FROM users WHERE username IN (?,?,?)",input.get(0).get(0),input.get(1).get(0),input.get(2).get(0))).isEqualTo(1);
        assertThatThrownBy(()->jobs.report(admin,"USER",id)).isInstanceOf(SecurityException.class);
    }
    @Test void fullTaskFiltersAndLastPageAreIndependentFromConfirm(){
        var data=new ArrayList<List<String>>();for(int i=0;i<21;i++)data.add(account());var bad=new ArrayList<>(account());bad.set(3,"abc");data.add(bad);String id=users(data);
        assertThat(jobs.rows(admin,"USER",id,"","",new PageRequest(1,20)).items()).hasSize(20);
        var last=jobs.rows(admin,"USER",id,"","",new PageRequest(Integer.MAX_VALUE,20));assertThat(last.page()).isEqualTo(2);assertThat(last.items()).hasSize(2);
        assertThat(jobs.rows(admin,"USER",id,data.get(20).get(0),"READY",new PageRequest(1,20)).items()).hasSize(1);
        assertThat(jobs.rows(admin,"USER",id,"","INVALID",new PageRequest(1,20)).totalItems()).isEqualTo(1);
        confirm(id,admin,"USER");try(var worker=worker()){assertThat(worker.runOnce()).isTrue();assertThat(worker.runOnce()).isFalse();}
        assertThat(jobs.progress(admin,"USER",id)).containsEntry("status","COMPLETED");assertThat(Sql.id(jobs.progress(admin,"USER",id).get("success_count"))).isEqualTo(21);
        verify(mail,times(21)).sendActivation(anyString(),anyString(),anyString(),anyString(),anyString());assertThat(state(id,23)).isEqualTo("INVALID");
        assertThat(Xlsx.read(jobs.report(admin,"USER",id))).hasSize(23);
    }
    @Test void confirmIsIdempotentButWrongOwnerKindKeyAndVersionAreRejected(){String id=users(List.of(account()));var j=jobs.find(admin,"USER",id);
        assertThatThrownBy(()->jobs.confirm(admin,"USER",id,2,Sql.text(j.get("confirm_key")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->jobs.confirm(admin,"USER",id,1,"wrong")).isInstanceOf(IllegalArgumentException.class);
        long other=user("ADMIN");assertThatThrownBy(()->jobs.find(other,"USER",id)).isInstanceOf(SecurityException.class);assertThatThrownBy(()->jobs.rows(other,"USER",id,"","",new PageRequest(1,20))).isInstanceOf(SecurityException.class);assertThatThrownBy(()->jobs.report(other,"USER",id)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(()->jobs.progress(admin,"PRODUCT",id)).isInstanceOf(SecurityException.class);
        confirm(id,admin,"USER");jobs.confirm(admin,"USER",id,1,Sql.text(j.get("confirm_key")));assertThat(count("SELECT COUNT(*) n FROM audit_logs WHERE event_type='IMPORT_JOB_CONFIRMED' AND actor_user_id=?",admin)).isEqualTo(1);
    }
    @Test void successCreatesPendingActivationAuditAndOutcomeOnSameCommit(){var input=account();String id=users(List.of(input));confirm(id,admin,"USER");try(var worker=worker()){worker.runOnce();}
        var result=one("SELECT entity_id,state,committed_at FROM import_job_rows WHERE job_id=? AND source_row=2",id);long user=Sql.id(result.get("entity_id"));assertThat(result.get("committed_at")).isNotNull();assertThat(result.get("state")).isEqualTo("SUCCESS");
        assertThat(one("SELECT status,must_change_password FROM users WHERE id=?",user)).containsEntry("status","PENDING_ACTIVATION").containsEntry("must_change_password",true);
        assertThat(count("SELECT COUNT(*) n FROM audit_logs WHERE object_id=? AND event_type='USER_CREATED'",user)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) n FROM import_mail_attempts WHERE job_id=? AND state='SUCCEEDED'",id)).isEqualTo(1);
        assertThat(jobs.progress(admin,"USER",id)).doesNotContainKeys("confirm_key","row_token","lease_owner","password","token");
        assertThat(query("SELECT * FROM import_mail_attempts WHERE job_id=?",id).toString()).doesNotContain("password","token=");
    }
    @Test void productUpdateRechecksExpectedVersionAndDoesNotOverwriteNewEdit(){var row=productRow(1);String first=products(List.of(row));confirm(first,manager,"PRODUCT");try(var worker=worker()){worker.runOnce();}
        String next=products(List.of(row));long entity=Sql.id(one("SELECT id FROM products WHERE sku=?",row.get(0)).get("id"));update("UPDATE products SET name='Tên sửa đồng thời',version=version+1 WHERE id=?",entity);confirm(next,manager,"PRODUCT");try(var worker=worker()){worker.runOnce();}
        assertThat(state(next,2)).isEqualTo("FAILED");assertThat(one("SELECT name FROM products WHERE id=?",entity).get("name")).isEqualTo("Tên sửa đồng thời");
    }
    @Test void failedAuditRollsBackAccountAndRowBusinessButKeepsFailureReport(){String id=users(List.of(account()));confirm(id,admin,"USER");failAudit();try(var worker=worker()){worker.runOnce();}
        assertThat(state(id,2)).isEqualTo("FAILED");assertThat(one("SELECT entity_id FROM import_job_rows WHERE job_id=? AND source_row=2",id).get("entity_id")).isNull();verifyNoInteractions(mail);
        assertThat(count("SELECT COUNT(*) n FROM import_mail_attempts WHERE job_id=? AND state='FAILED'",id)).isEqualTo(1);
    }
    @Test void definitiveSmtpFailureRollsBackAndExplicitNewJobCanRetry(){var input=account();doThrow(new MailDeliveryFailure(true,new IllegalStateException("SMTP rejected"))).when(mail).sendActivation(anyString(),anyString(),anyString(),anyString(),anyString());String id=users(List.of(input));confirm(id,admin,"USER");try(var worker=worker()){worker.runOnce();}assertThat(state(id,2)).isEqualTo("FAILED");assertThat(count("SELECT COUNT(*) n FROM users WHERE username=?",input.get(0))).isZero();
        doNothing().when(mail).sendActivation(anyString(),anyString(),anyString(),anyString(),anyString());String retry=users(List.of(input));confirm(retry,admin,"USER");try(var worker=worker()){worker.runOnce();}assertThat(state(retry,2)).isEqualTo("SUCCESS");
    }
    @Test void ambiguousSmtpNeverResendsEvenThroughAnotherUploadOrReportCleanup(){var input=account();doThrow(new MailDeliveryFailure(false,new java.net.SocketTimeoutException())).when(mail).sendActivation(anyString(),anyString(),anyString(),anyString(),anyString());String id=users(List.of(input));confirm(id,admin,"USER");try(var worker=worker()){worker.runOnce();worker.runOnce();}
        assertThat(state(id,2)).isEqualTo("REVIEW");assertThat(count("SELECT COUNT(*) n FROM users WHERE username=?",input.get(0))).isZero();update("UPDATE import_jobs SET report_expires_at=TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",id);assertThat(jobs.cleanup()).isEqualTo(1);
        String retry=users(List.of(input));confirm(retry,admin,"USER");try(var worker=worker()){worker.runOnce();}assertThat(state(retry,2)).isEqualTo("FAILED");verify(mail,times(1)).sendActivation(anyString(),anyString(),anyString(),anyString(),anyString());assertThat(count("SELECT COUNT(*) n FROM import_mail_attempts WHERE job_id=? AND state='REVIEW'",id)).isEqualTo(1);
    }
    @Test void outcomeFailureAfterAcceptedMailRollsBackAccountAndRequiresReview(){var input=account();String id=users(List.of(input));confirm(id,admin,"USER");update("CREATE TRIGGER import_outcome_fail BEFORE UPDATE ON import_job_rows FOR EACH ROW BEGIN IF NEW.state='SUCCESS' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='outcome rejected'; END IF; END");try(var worker=worker()){worker.runOnce();}
        assertThat(state(id,2)).isEqualTo("REVIEW");assertThat(count("SELECT COUNT(*) n FROM users WHERE username=?",input.get(0))).isZero();assertThat(count("SELECT COUNT(*) n FROM import_mail_attempts WHERE job_id=? AND state='REVIEW'",id)).isEqualTo(1);verify(mail,times(1)).sendActivation(anyString(),anyString(),anyString(),anyString(),anyString());
    }
    @Test void restartKeepsCommittedRowsRecoversClaimedAndReviewsEmailMarker(){var data=List.of(account(),account(),account());String id=users(data);confirm(id,admin,"USER");ImportWorker[] first={worker()};doAnswer(call->{first[0].close();return null;}).when(mail).sendActivation(anyString(),anyString(),anyString(),anyString(),anyString());first[0].runOnce();assertThat(state(id,2)).isEqualTo("SUCCESS");
        String attempt=UUID.randomUUID().toString();update("INSERT INTO import_mail_attempts(id,job_id,source_row,actor_id,username_normalized) VALUES(?,?,3,?,?)",attempt,id,admin,data.get(1).get(0));update("UPDATE import_job_rows SET state='EMAIL_ATTEMPT',row_token=?,mail_attempt_id=? WHERE job_id=? AND source_row=3",UUID.randomUUID().toString(),attempt,id);update("UPDATE import_job_rows SET state='CLAIMED',row_token=? WHERE job_id=? AND source_row=4",UUID.randomUUID().toString(),id);doNothing().when(mail).sendActivation(anyString(),anyString(),anyString(),anyString(),anyString());try(var second=worker()){second.runOnce();}
        assertThat(state(id,2)).isEqualTo("SUCCESS");assertThat(state(id,3)).isEqualTo("REVIEW");assertThat(state(id,4)).isEqualTo("SUCCESS");verify(mail,times(2)).sendActivation(anyString(),anyString(),anyString(),anyString(),anyString());
    }
    @Test void twoProcessesCannotExecuteSameRows(){var data=new ArrayList<List<String>>();for(int i=0;i<10;i++)data.add(account());String id=users(data);confirm(id,admin,"USER");var threads=Executors.newFixedThreadPool(2);try(var a=worker();var b=worker()){var one=threads.submit(a::runOnce);var two=threads.submit(b::runOnce);assertThat(one.get(30,TimeUnit.SECONDS)||two.get(30,TimeUnit.SECONDS)).isTrue();assertThat(Sql.id(jobs.progress(admin,"USER",id).get("success_count"))).isEqualTo(10);verify(mail,times(10)).sendActivation(anyString(),anyString(),anyString(),anyString(),anyString());}catch(Exception e){throw new AssertionError(e);}finally{threads.shutdownNow();}}
    @Test void actorLockBeforeExecutionStopsAllRemainingRows(){String id=users(List.of(account(),account()));confirm(id,admin,"USER");update("UPDATE users SET status='ADMIN_LOCKED' WHERE id=?",admin);try(var worker=worker()){worker.runOnce();}assertThat(state(id,2)).isEqualTo("SKIPPED");assertThat(state(id,3)).isEqualTo("SKIPPED");assertThat(one("SELECT status FROM import_jobs WHERE id=?",id).get("status")).isEqualTo("STOPPED");verifyNoInteractions(mail);assertThatThrownBy(()->jobs.progress(admin,"USER",id)).isInstanceOf(SecurityException.class);}
    @Test void expiredPreviewDoesNotQueueAndRunningJobsAreNeverCleaned(){String id=users(List.of(account()));update("UPDATE import_jobs SET preview_expires_at=TIMESTAMPADD(MINUTE,-1,CURRENT_TIMESTAMP(6)) WHERE id=?",id);assertThatThrownBy(()->confirm(id,admin,"USER")).isInstanceOf(IllegalArgumentException.class);jobs.cleanup();assertThat(one("SELECT status FROM import_jobs WHERE id=?",id).get("status")).isEqualTo("EXPIRED");String running=users(List.of(account()));confirm(running,admin,"USER");update("UPDATE import_jobs SET status='RUNNING',report_expires_at=TIMESTAMPADD(DAY,-1,CURRENT_TIMESTAMP(6)),lease_until=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP(6)) WHERE id=?",running);jobs.cleanup();assertThat(count("SELECT COUNT(*) n FROM import_jobs WHERE id=?",running)).isEqualTo(1);}
    @Test void previewOwnershipAndDuplicateRowNumbersCannotBePersisted(){var row=account();var preview=new ImportPreview(admin,List.of(new ImportPreview.Line(2,row,"Tạo mới","")),clock.instant());assertThatThrownBy(()->jobs.create(user("ADMIN"),"USER","file.xlsx",preview)).isInstanceOf(IllegalArgumentException.class);var duplicates=new ImportPreview(admin,List.of(new ImportPreview.Line(2,row,"Tạo mới",""),new ImportPreview.Line(2,row,"Tạo mới","")),clock.instant());assertThatThrownBy(()->jobs.create(admin,"USER","file.xlsx",duplicates)).isInstanceOf(IllegalArgumentException.class);assertThat(count("SELECT COUNT(*) n FROM import_jobs WHERE actor_id=?",admin)).isZero();}
    @Test void reportCostProjectionUsesCurrentActualRole(){String id=products(List.of(productRow(1)));confirm(id,manager,"PRODUCT");try(var worker=worker()){worker.runOnce();}long role=insert("INSERT INTO roles(code,name) VALUES(?,'Vai trò QA nhập')","IMP-"+UUID.randomUUID());update("INSERT INTO role_permissions(role_id,permission_id) SELECT ?,id FROM permissions WHERE code IN ('PRODUCT_MANAGE','COST_READ')",role);update("DELETE FROM user_roles WHERE user_id=?",manager);update("INSERT INTO user_roles(user_id,role_id) VALUES(?,?)",manager,role);
        assertThat(jobs.rows(manager,"PRODUCT",id,"","",new PageRequest(1,20)).items().get(0).get("cells")).asList().hasSize(6);var report=Xlsx.read(jobs.report(manager,"PRODUCT",id));assertThat(report.get(0).cells()).doesNotContain("Giá vốn");assertThat(report.toString()).doesNotContain("123.4567");
    }
    @Test void actualWorkbookWith5000ProductsIsPersistedAndExecutedAcrossAllPages(){var input=new ArrayList<List<String>>();String code=Sql.text(one("SELECT code FROM categories WHERE id=?",category).get("code")),prefix="B-"+UUID.randomUUID()+"-";for(int i=0;i<5000;i++)input.add(List.of(prefix+i,"Sản phẩm nhập thật "+i,code,"Cái","","ACTIVE","12.0001"));String id=products(input);assertThat(jobs.rows(manager,"PRODUCT",id,"","",new PageRequest(250,20)).items()).hasSize(20);confirm(id,manager,"PRODUCT");try(var worker=worker()){worker.runOnce();}
        assertThat(Sql.id(jobs.progress(manager,"PRODUCT",id).get("success_count"))).isEqualTo(5000);assertThat(count("SELECT COUNT(*) n FROM products WHERE sku LIKE ?",prefix+"%")).isEqualTo(5000);assertThat(count("SELECT COUNT(*) n FROM import_job_rows r JOIN audit_logs a ON a.object_id=r.entity_id AND a.event_type='PRODUCT_SAVED' WHERE r.job_id=? AND r.state='SUCCESS'",id)).isEqualTo(5000);assertThat(Xlsx.read(jobs.report(manager,"PRODUCT",id))).hasSize(5001);
    }
}
