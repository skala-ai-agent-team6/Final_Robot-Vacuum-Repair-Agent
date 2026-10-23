package com.skala.backend.repair;

import com.skala.backend.repair.quality.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import java.io.*;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest(properties={"app.scheduling.enabled=false","app.mock-ai.enabled=false","app.photo-quality.enabled=true","app.internal-token=test-service-secret-at-least-32-characters","app.seed-password=TestPassword-Only123!","app.files=${java.io.tmpdir}/repair-photo-integration"})
@ActiveProfiles("dev") @AutoConfigureMockMvc @Testcontainers
class PhotoQualityIntegrationTests {
    @Container static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17-alpine");
    @Container static KafkaContainer kafka=new KafkaContainer("apache/kafka:4.1.1");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);r.add("spring.kafka.bootstrap-servers",kafka::getBootstrapServers);}
    @Autowired Store db;@Autowired RepairService repairs;@Autowired PhotoQualityService quality;@Autowired PhotoQualityWorker worker;@Autowired PhotoQualityRepository repository;@Autowired MockMvc mvc;@Autowired DiagnosisEvents events;
    @MockitoSpyBean PhotoQualityChecker checker;
    @BeforeEach void setup() {reset(checker);db.update("TRUNCATE repair_case CASCADE");login("tech1");}
    @AfterEach void clean() {SecurityContextHolder.clearContext();}
    void login(String name) {SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name,"unused",List.of()));}
    MockMultipartFile good() {return new MockMultipartFile("file","arbitrary.exe","image/png",QualityImages.png(640,480));}
    MockMultipartFile badQuality() {return new MockMultipartFile("file","black.png","image/png",QualityImages.bytes(new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB),"png"));}
    Map<String,Object> create(MockMultipartFile file) {return repairs.create(new Inputs.Intake(1L,"symptom",List.of(),"1",Map.of()),List.of(file));}
    Long attachment(Map<String,Object> created) {return (Long)((Map<?,?>)((List<?>)created.get("attachments")).getFirst()).get("attachmentId");}
    Long repair(Map<String,Object> created) {return (Long)created.get("repairId");}
    Long result(Long id) {return (Long)db.one("SELECT latest_quality_result_id FROM attachment WHERE id=?",id).get("latest_quality_result_id");}
    Map<String,Object> run(Long id) {worker.runOnce();await().atMost(Duration.ofSeconds(15)).until(()->Set.of("COMPLETED","ERROR").contains(db.one("SELECT status FROM photo_quality_result WHERE id=?",result(id)).get("status")));return db.one("SELECT * FROM photo_quality_result WHERE id=?",result(id));}
    void error(Runnable action,String code) {assertThatThrownBy(action::run).isInstanceOf(ApiError.class).hasMessage(code);}
    String photoRequest(Long repair,Long job,UUID key) {
        var j=db.one("SELECT * FROM diagnosis_job WHERE id=?",job);return Json.write(Map.ofEntries(Map.entry("eventId",UUID.randomUUID()),Map.entry("eventType","PHOTO_REQUESTED"),Map.entry("schemaVersion",2),Map.entry("occurredAt",java.time.Instant.now().toString()),Map.entry("repairId",repair),Map.entry("taskId",job),Map.entry("correlationId",db.one("SELECT correlation_id FROM repair_case WHERE id=?",repair).get("correlation_id")),Map.entry("executionId",j.get("execution_id")),Map.entry("inputRevision",j.get("input_revision")),Map.entry("sequence",1),Map.entry("payload",Map.of("requestKey",key,"round",1,"bodyPart","wheel","description","photo","checkpoint","cp"))));
    }
    @Test void integratedTargetsAndApprovalRemainAvailableWithQualityEnabled() {
        var c=repairs.create(new Inputs.Intake(1L,"symptom",List.of(),"1",Map.of()),List.of(good(),good()),List.of("PCB","MECHANICAL"));
        Long caseId=repair(c);
        assertThat(db.list("SELECT target FROM attachment WHERE case_id=? ORDER BY id",caseId).stream().map(a->a.get("target"))).containsExactly("PCB","MOTOR");
        var added=repairs.upload(caseId,null,good(),"SW");
        assertThat(db.one("SELECT target FROM attachment WHERE id=?",added.get("attachmentId")).get("target")).isEqualTo("SW");
        db.update("UPDATE repair_case SET status='DIAGNOSED' WHERE id=?",caseId);
        repairs.approval(caseId,new Inputs.Approval("DIAGNOSIS","APPROVE","confirmed"));
        assertThat(db.one("SELECT status FROM repair_case WHERE id=?",caseId).get("status")).isEqualTo("REPAIRING");
    }
    @Test void saveFirstThenInspectAndOnlyExplicitlySelectedPhotosReachKafka() throws Exception {
        doAnswer(invocation->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return invocation.callRealMethod();}).when(checker).check(any());
        var c=create(good());Long id=attachment(c);assertThat(c.get("taskId")).isNull();assertThat(db.list("SELECT * FROM diagnosis_job")).isEmpty();assertThat(db.list("SELECT * FROM outbox_event WHERE topic IS NOT NULL")).isEmpty();
        error(()->repairs.start(repair(c)),"PHOTO_SELECTION_REQUIRED");error(()->repairs.start(repair(c),null,List.of(id)),"PHOTO_QUALITY_PENDING");assertThat(db.list("SELECT * FROM diagnosis_job")).isEmpty();
        assertThat(run(id).get("verdict")).isEqualTo("PASS");error(()->quality.retry(id,result(id)),"QUALITY_RECHECK_REQUIRES_NEW_POLICY");var extra=repairs.upload(repair(c),null,badQuality());Long excluded=(Long)extra.get("attachmentId");
        var started=repairs.start(repair(c),null,List.of(id));assertThat(started.get("taskId")).isNotNull();
        var payload=(Map<?,?>)((Map<?,?>)db.one("SELECT payload FROM outbox_event WHERE topic IS NOT NULL").get("payload")).get("payload");assertThat(payload.get("attachmentIds")).isEqualTo(List.of(id.toString()));assertThat(payload.get("excludedAttachmentIds")).isEqualTo(List.of(excluded.toString()));
        repairs.start(repair(c),null,List.of(id));error(()->repairs.start(repair(c),null,List.of(excluded)),"DIAGNOSIS_SELECTION_CHANGED");
        assertThat(db.list("SELECT * FROM diagnosis_input_attachment")).hasSize(1);
        mvc.perform(get("/api/attachments/"+id+"/quality").with(user("tech1"))).andExpect(status().isOk()).andExpect(jsonPath("$.latest.verdict").value("PASS"));
    }
    @Test void warningRequiresExactLatestAcknowledgmentAndCannotBeForged() throws Exception {
        var c=create(badQuality());Long id=attachment(c);var q=run(id);assertThat(q.get("verdict")).isEqualTo("WARNING");assertThat(((List<?>)q.get("issues")).stream().map(Object::toString).toList()).contains("LOW_RESOLUTION","TOO_DARK","BLURRY");
        error(()->repairs.start(repair(c),null,List.of(id)),"PHOTO_WARNING_NOT_CONFIRMED");
        mvc.perform(post("/api/attachments/"+id+"/quality/acknowledgment").with(user("tech1")).with(csrf()).contentType("application/json").content("{\"resultId\":\""+result(id)+"\",\"verdict\":\"PASS\",\"sharpness\":9999}")).andExpect(status().isOk());
        login("tech1");quality.acknowledge(id,result(id));assertThat(db.list("SELECT * FROM photo_quality_confirmation")).hasSize(1);
        var job=repairs.start(repair(c),null,List.of(id));assertThat(db.one("SELECT warning_confirmation_id FROM diagnosis_input_attachment WHERE job_id=?",job.get("taskId")).get("warning_confirmation_id")).isNotNull();
        // Simulate a new policy run; an earlier confirmation cannot authorize its result.
        Long previous=result(id);Long next=db.insert("INSERT INTO photo_quality_result(attachment_id,previous_result_id,attempt,checker_version,policy_version,status,verdict,issues) VALUES (?,?,2,?,?,'COMPLETED','WARNING','[\"BLURRY\"]')",id,previous,q.get("checker_version"),q.get("policy_version"));db.update("UPDATE attachment SET latest_quality_result_id=? WHERE id=?",next,id);
        error(()->quality.acknowledge(id,previous),"QUALITY_RESULT_CHANGED");
        error(()->quality.freeze(repair(c),(Long)job.get("taskId"),2,List.of(id),List.of(id)),"PHOTO_WARNING_NOT_CONFIRMED");
    }
    @Test void qualityErrorsAreNotWarningsAndRetriesAreBoundedAndIdempotent() throws Exception {
        var c=create(good());Long id=attachment(c);doThrow(new IOException("inspection test")).when(checker).check(any());
        var failed=run(id);assertThat(failed.get("status")).isEqualTo("ERROR");assertThat(failed.get("verdict")).isNull();error(()->repairs.start(repair(c),null,List.of(id)),"PHOTO_QUALITY_ERROR");
        Long first=result(id);quality.retry(id,first);Long second=result(id);quality.retry(id,first);assertThat(result(id)).isEqualTo(second);assertThat(db.list("SELECT * FROM photo_quality_result")).hasSize(2);
        run(id);quality.retry(id,second);run(id);error(()->quality.retry(id,result(id)),"QUALITY_RETRY_LIMIT");
        assertThat(db.list("SELECT * FROM photo_quality_result")).hasSize(3);assertThat(db.list("SELECT * FROM outbox_event WHERE event_type='PHOTO_QUALITY_ERROR'")).hasSize(3);
    }
    @Test void concurrentRetryCreatesOnlyOneNewRun() throws Exception {
        var c=create(good());Long id=attachment(c);doThrow(new IOException("test")).when(checker).check(any());run(id);Long previous=result(id);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);List<Future<?>> results=new ArrayList<>();
            for(int i=0;i<2;i++) results.add(pool.submit(()->{login("tech1");barrier.await();try{return quality.retry(id,previous);}finally{SecurityContextHolder.clearContext();}}));
            for(var future:results) future.get(10,TimeUnit.SECONDS);
        }
        assertThat(db.list("SELECT * FROM photo_quality_result")).hasSize(2);assertThat(db.list("SELECT * FROM photo_quality_result WHERE status='PENDING'")).hasSize(1);
    }
    @Test void extraPhotoRetakesDoNotSpendAnotherAgentRoundAndOnlySelectedResumeIsSent() {
        var c=create(good());Long first=attachment(c);run(first);Long job=(Long)repairs.start(repair(c),null,List.of(first)).get("taskId");UUID key=UUID.randomUUID();events.receive(photoRequest(repair(c),job,key));Long request=(Long)db.one("SELECT id FROM photo_request WHERE request_key=?",key).get("id");
        Long old=(Long)repairs.upload(repair(c),request,badQuality()).get("attachmentId");var input=new Inputs.Resume(request,UUID.randomUUID(),List.of(old));error(()->repairs.resume(repair(c),input),"PHOTO_QUALITY_PENDING");run(old);error(()->repairs.resume(repair(c),input),"PHOTO_WARNING_NOT_CONFIRMED");
        Long replacement=(Long)repairs.upload(repair(c),request,good()).get("attachmentId");run(replacement);quality.selection(old,true,replacement);
        var resume=new Inputs.Resume(request,UUID.randomUUID(),List.of(replacement));repairs.resume(repair(c),resume);repairs.resume(repair(c),resume);
        assertThat(db.one("SELECT photo_request_count,input_revision FROM diagnosis_job WHERE id=?",job).get("photo_request_count")).isEqualTo(1);
        assertThat(db.list("SELECT * FROM photo_request")).hasSize(1);assertThat(db.list("SELECT * FROM outbox_event WHERE event_type='DIAGNOSIS_RESUMED' AND topic IS NOT NULL")).hasSize(1);
        assertThat(db.list("SELECT attachment_id FROM diagnosis_input_attachment WHERE job_id=? AND input_revision=2 ORDER BY attachment_id",job)).extracting(a->a.get("attachment_id")).containsExactly(first,replacement);
    }
    @Test void centerAndRoleBoundariesApplyToEveryQualityMutation() throws Exception {
        var c=create(badQuality());Long id=attachment(c);run(id);
        mvc.perform(get("/api/attachments/"+id+"/quality").with(user("tech2"))).andExpect(status().isForbidden());
        for(String action:List.of("acknowledgment","retry")) mvc.perform(post("/api/attachments/"+id+"/quality/"+action).with(user("tech2")).with(csrf()).contentType("application/json").content("{\"resultId\":\""+result(id)+"\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/attachments/"+id+"/selection").with(user("tech2")).with(csrf()).contentType("application/json").content("{\"excluded\":true}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/attachments/"+id+"/quality/acknowledgment").with(user("quality")).with(csrf()).contentType("application/json").content("{\"resultId\":\""+result(id)+"\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/attachments/"+id+"/quality").with(user("quality"))).andExpect(status().isOk());
    }
    @Test void legacyAttachmentsAreExplicitlyGrandfatheredAndExcludedPhotosCannotBeSelected() {
        var c=create(good());Long id=attachment(c);db.update("UPDATE attachment SET latest_quality_result_id=NULL,quality_required=false WHERE id=?",id);assertThat(quality.get(id).get("status")).isEqualTo("LEGACY_UNCHECKED");
        quality.selection(id,true,null);error(()->repairs.start(repair(c),null,List.of(id)),"ATTACHMENT_EXCLUDED");quality.selection(id,false,null);
        repairs.start(repair(c),null,List.of(id));assertThat(db.one("SELECT quality_gate FROM diagnosis_input_attachment").get("quality_gate")).isEqualTo("LEGACY");
    }
    @Test void expiredWorkersAreFencedAndCannotOverwriteRecoveredRun() {
        var c=create(good());Long id=attachment(c);var old=repository.claim();db.update("UPDATE photo_quality_result SET lease_until=now()-interval '1 second' WHERE id=?",old.get("id"));repository.recover();var fresh=repository.claim();
        repository.complete(old,null,1,"LATE_ERROR");assertThat(db.one("SELECT status FROM photo_quality_result WHERE id=?",result(id)).get("status")).isEqualTo("RUNNING");
        var ok=new PhotoQualityChecker.Result(640,480,640,480,128,0,0,1000,List.of());repository.complete(fresh,ok,5,null);assertThat(db.one("SELECT verdict,failure_code FROM photo_quality_result WHERE id=?",result(id)).get("verdict")).isEqualTo("PASS");
    }
    @Test void invalidImagesAndOversizeAreRejectedBeforeAnyDatabaseWrite() {
        error(()->create(new MockMultipartFile("file","fake.jpg","image/jpeg",new byte[]{(byte)255,(byte)216,(byte)255})),"IMAGE_DECODE_FAILED");
        assertThatThrownBy(()->create(new MockMultipartFile("file","fake.jpg","image/jpeg","not an image".getBytes()))).isInstanceOf(ApiError.class);
        var oversized=new MockMultipartFile("file","large.png","image/png",QualityImages.png(1,1)){@Override public long getSize(){return 10485761;}};error(()->create(oversized),"FILE_SIZE");assertThat(db.list("SELECT * FROM repair_case")).isEmpty();
    }
    @Test void measureHttpLatencyWhileInspectionIsBusyWithoutWaitingForItsCompletion() throws Exception {
        var c=create(good());Long id=attachment(c);var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(invocation->{entered.countDown();release.await(10,TimeUnit.SECONDS);return invocation.callRealMethod();}).when(checker).check(any());
        worker.runOnce();assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
        try {
            double[] times=new double[5];for(int i=0;i<5;i++){long start=System.nanoTime();mvc.perform(get("/api/attachments/"+id+"/quality").with(user("tech1"))).andExpect(status().isOk());times[i]=(System.nanoTime()-start)/1_000_000.0;}
            java.util.Arrays.sort(times);long start=System.nanoTime();
            mvc.perform(multipart("/api/repairs/"+repair(c)+"/attachments").file(good()).with(user("tech1")).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.quality.status").value("PENDING"));
            System.out.printf(java.util.Locale.ROOT,"PHOTO_API_BENCH transport=MockMvc workers=2 busyInspection=1 getRuns=5 getMedianMs=%.3f getMaxMs=%.3f uploadMs=%.3f%n",times[2],times[4],(System.nanoTime()-start)/1_000_000.0);
        } finally {release.countDown();}
        await().atMost(Duration.ofSeconds(15)).until(()->db.one("SELECT status FROM photo_quality_result WHERE id=?",result(id)).get("status").equals("COMPLETED"));
    }

}
