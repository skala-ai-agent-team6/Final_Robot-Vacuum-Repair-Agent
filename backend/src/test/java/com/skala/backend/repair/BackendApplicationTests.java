package com.skala.backend.repair;

import com.skala.backend.repair.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"app.scheduling.enabled=false","app.photo-quality.enabled=false","app.mock-ai.enabled=false","app.internal-token=test-service-secret-at-least-32-characters","app.seed-password=TestPassword-Only123!","app.files=${java.io.tmpdir}/repair-test-files"})
@ActiveProfiles("dev")
@AutoConfigureMockMvc
@Testcontainers
class BackendApplicationTests {
    @Container static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17-alpine");
    @Container static KafkaContainer kafka=new KafkaContainer("apache/kafka:4.1.1");
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",postgres::getJdbcUrl);r.add("spring.datasource.username",postgres::getUsername);r.add("spring.datasource.password",postgres::getPassword);
        r.add("spring.kafka.bootstrap-servers",kafka::getBootstrapServers);
    }
    @Autowired Store db; @Autowired RepairService repairs; @Autowired ReportService reports; @Autowired OrderService orders;
    @MockitoSpyBean DiagnosisEvents events;
    @Autowired DashboardService dashboard; @Autowired MockMvc mvc; @Autowired SseHub hub;
    @Autowired PlatformTransactionManager manager; @Autowired KafkaTemplate<String,String> producer;
    static final Long PRODUCT=1L;
    static final Long PART=1L;
    static final Long COMPONENT=1L;
    static final Long WAREHOUSE=1L;
    @BeforeEach void clean() {
        reset(events);db.update("TRUNCATE repair_case CASCADE");db.update("UPDATE inventory SET reserved=0,on_hand=10");login("tech1");
    }
    @AfterEach void logout() {SecurityContextHolder.clearContext();}
    void login(String user) {SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,"unused",List.of()));}
    MockMultipartFile photo() {return new MockMultipartFile("file","wheel.png","image/png",com.skala.backend.repair.quality.QualityImages.png(1,1));}
    Map<String,Object> create() {return repairs.create(new Inputs.Intake(PRODUCT,"원문 증상",List.of("E01","E02"),"1.2",Map.of("custom","원문","id",123,"nested",Map.of("partId",7))),List.of(photo()));}
    Long repair(Map<String,Object> c) {return (Long)c.get("repairId");}
    Long task(Map<String,Object> c) {return (Long)c.get("taskId");}
    String event(Map<String,Object> c,int seq,String type,Map<String,Object> payload) {
        var t=db.one("SELECT * FROM diagnosis_job WHERE id=?",task(c));
        var m=new LinkedHashMap<String,Object>();m.put("eventId",UUID.randomUUID());m.put("eventType",type);m.put("schemaVersion",2);m.put("occurredAt",Instant.now().toString());m.put("repairId",repair(c));m.put("taskId",task(c));m.put("correlationId",db.one("SELECT correlation_id FROM repair_case WHERE id=?",repair(c)).get("correlation_id"));m.put("executionId",t.get("execution_id"));m.put("inputRevision",t.get("input_revision"));m.put("sequence",seq);m.put("payload",payload);return Json.write(m);
    }
    Long photoId(UUID key) {return (Long)db.one("SELECT id FROM photo_request WHERE request_key=?",key).get("id");}
    Map<String,Object> result() {return Map.of("summary","motor","modelVersion","test-1","repairMethod","replace","candidates",List.of(Map.of("rank",1,"confidence",0.8,"cause","motor","explanation","inspection")),"recommendedParts",List.of(Map.of("partId",PART,"quantity",1)),"evidence",List.of());}
    Inputs.ReportContent content(String area) {return new Inputs.ReportContent("실제 원인",List.of(new Inputs.Fault(area,area.equals("SW")?null:COMPONENT,area.equals("SW")?null:PART)),"교체","기사 의견",true,Inputs.Acceptance.PARTIAL);}
    long count(String area) {return dashboard.counts(LocalDate.now().minusDays(1),LocalDate.now().plusDays(2),"AREA").stream().filter(r->area.equals(r.get("dimension"))).mapToLong(r->((Number)r.get("count")).longValue()).sum();}
    Long proposal(Long repair,int quantity) {return (Long)orders.propose(repair,new Inputs.Proposal(List.of(new Inputs.Item(PART,quantity,Inputs.Source.WAREHOUSE,WAREHOUSE,null)))).get("id");}
    @Test void loginCsrfAndServiceBoundary() throws Exception {
        mvc.perform(get("/api/repairs").with(anonymous())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").with(anonymous()).with(csrf()).param("username","tech1").param("password","TestPassword-Only123!")).andExpect(status().isOk());
        mvc.perform(get("/internal/parts").with(user("quality"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/parts").header("Authorization","Bearer test-service-secret-at-least-32-characters")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").with(anonymous()).param("username","tech1").param("password","TestPassword-Only123!")).andExpect(status().isForbidden());
    }
    @Test void centersAndRolesAreEnforced() throws Exception {
        var c=create();var d=reports.draft(repair(c),new Inputs.Draft(0,content("MOTOR")));Long report=(Long)d.get("id");Long order=proposal(repair(c),1);
        mvc.perform(get("/api/repairs/"+repair(c)).with(user("tech2"))).andExpect(status().isForbidden());
        var a=db.one("SELECT id FROM attachment WHERE case_id=?",repair(c));
        mvc.perform(get("/api/attachments/"+a.get("id")).with(user("tech2"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/orders/"+order+"/approve").with(user("tech1")).with(csrf()).contentType("application/json").content("{\"reason\":\"yes\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/reports/"+report+"/comments").with(user("tech1")).with(csrf()).contentType("application/json").content(Json.write(new Inputs.Comment(1,"comment",UUID.randomUUID())))).andExpect(status().isForbidden());
        login("quality");assertThatThrownBy(()->repairs.start(repair(c))).isInstanceOf(ApiError.class);
    }
    @Test void intakeIsAtomicAndInputRemainsUnchanged() {
        assertThatThrownBy(()->repairs.create(new Inputs.Intake(PRODUCT,"x",List.of(),"1",Map.of()),List.of(new MockMultipartFile("file","x.exe","application/octet-stream",new byte[]{0,0,0})))).isInstanceOf(ApiError.class);
        assertThat(db.list("SELECT * FROM repair_case")).isEmpty();assertThat(db.list("SELECT * FROM outbox_event WHERE topic IS NOT NULL")).isEmpty();
        var c=create();var r=db.one("SELECT * FROM repair_case WHERE id=?",repair(c));assertThat(r.get("symptom")).isEqualTo("원문 증상");assertThat(r.get("user_input")).isEqualTo(Map.of("custom","원문","id",123,"nested",Map.of("partId",7)));
        assertThat(repairs.start(repair(c)).get("taskId")).isEqualTo(task(c));
        assertThat(db.list("SELECT * FROM diagnosis_job")).hasSize(1);assertThat(db.list("SELECT * FROM outbox_event WHERE topic IS NOT NULL")).hasSize(1);
        var outbox_event=db.one("SELECT payload FROM outbox_event WHERE topic IS NOT NULL");assertThat(Json.write(outbox_event.get("payload"))).contains("원문 증상","attachmentIds","/internal/attachments/");
    }
    @Test void duplicatesOrderAndOldExecutionsCannotOverwrite() {
        var c=create();String progress=event(c,2,"DIAGNOSIS_PROGRESS",Map.of("stage","RUNNING"));events.receive(progress);events.receive(progress);
        events.receive(event(c,1,"DIAGNOSIS_STARTED",Map.of("stage","LATE")));
        assertThat(db.one("SELECT * FROM diagnosis_job WHERE id=?",task(c)).get("stage")).isEqualTo("RUNNING");
        assertThat(db.list("SELECT * FROM received_event")).hasSize(2);
        String old=event(c,99,"DIAGNOSIS_COMPLETED",result());UUID requestKey=UUID.randomUUID();
        events.receive(event(c,3,"PHOTO_REQUESTED",Map.of("requestKey",requestKey,"round",1,"bodyPart","wheel","description","photo","checkpoint","cp")));
        var a=repairs.upload(repair(c),photoId(requestKey),photo());repairs.resume(repair(c),new Inputs.Resume(photoId(requestKey),UUID.randomUUID(),List.of((Long)a.get("attachmentId"))));
        events.receive(old);assertThat(db.one("SELECT status FROM diagnosis_job WHERE id=?",task(c)).get("status")).isEqualTo("QUEUED");
    }
    @Test void photosResumeSameTaskAndOnlyThreeRounds() {
        var c=create();
        for(int round=1;round<=3;round++) {
            UUID requestKey=UUID.randomUUID();events.receive(event(c,1,"PHOTO_REQUESTED",Map.of("requestKey",requestKey,"round",round,"bodyPart","wheel","description","photo","checkpoint","cp")));
            var before=db.one("SELECT * FROM diagnosis_job WHERE id=?",task(c));assertThat(before.get("status")).isEqualTo("WAITING_INPUT");assertThat(before.get("retry_count")).isEqualTo(0);
            var a=repairs.upload(repair(c),photoId(requestKey),photo());var input=new Inputs.Resume(photoId(requestKey),UUID.randomUUID(),List.of((Long)a.get("attachmentId")));
            repairs.resume(repair(c),input);repairs.resume(repair(c),input);
        }
        events.receive(event(c,1,"PHOTO_REQUESTED",Map.of()));
        assertThat(db.one("SELECT status FROM diagnosis_job WHERE id=?",task(c)).get("status")).isEqualTo("FAILED");
        assertThat(db.one("SELECT status FROM repair_case WHERE id=?",repair(c)).get("status")).isEqualTo("MANUAL_REVIEW");
        assertThat(db.list("SELECT * FROM outbox_event WHERE topic IS NOT NULL")).hasSize(4);assertThat(db.list("SELECT * FROM diagnosis_job")).hasSize(1);
    }
    @Test void foreignPhotosAndSubmissionKeyReuseRejected() {
        var c=create();UUID requestKey=UUID.randomUUID();events.receive(event(c,1,"PHOTO_REQUESTED",Map.of("requestKey",requestKey,"round",1,"bodyPart","wheel","description","photo","checkpoint","cp")));
        var another=create();Long foreign=(Long)db.one("SELECT id FROM attachment WHERE case_id=?",repair(another)).get("id");
        assertThatThrownBy(()->repairs.resume(repair(c),new Inputs.Resume(photoId(requestKey),UUID.randomUUID(),List.of(foreign)))).isInstanceOf(ApiError.class);
        var a=repairs.upload(repair(c),photoId(requestKey),photo());UUID key=UUID.randomUUID();repairs.resume(repair(c),new Inputs.Resume(photoId(requestKey),key,List.of((Long)a.get("attachmentId"))));
        assertThatThrownBy(()->repairs.resume(repair(c),new Inputs.Resume(photoId(requestKey),key,List.of(foreign)))).isInstanceOf(ApiError.class);
    }
    @Test void inventoryFailurePreservesDiagnosisSuccess() {
        var c=create();events.receive(event(c,1,"DIAGNOSIS_COMPLETED",result()));
        BusinessMcp failing=ids->{throw new IllegalStateException("offline");};new ProcurementWorker(db,failing,orders,manager).run();
        assertThat(db.one("SELECT status FROM diagnosis_job WHERE id=?",task(c)).get("status")).isEqualTo("SUCCEEDED");
        assertThat(db.one("SELECT procurement_status FROM repair_case WHERE id=?",repair(c)).get("procurement_status")).isEqualTo("FAILED");
    }
    @Test void approvalReservationCancellationAndShipping() {
        var c=create();Long id=proposal(repair(c),3);login("quality");orders.transition(id,"approve",new Inputs.Decision("approved"));orders.transition(id,"approve",new Inputs.Decision("retry"));
        assertThat(db.one("SELECT reserved FROM inventory WHERE part_id=?",PART).get("reserved")).isEqualTo(3);
        login("tech1");orders.transition(id,"cancel",new Inputs.Decision("cancel"));assertThat(db.one("SELECT reserved FROM inventory WHERE part_id=?",PART).get("reserved")).isEqualTo(0);
        Long shipping=proposal(repair(c),2);login("quality");orders.transition(shipping,"approve",new Inputs.Decision("yes"));login("tech1");orders.transition(shipping,"ship",new Inputs.Decision("shipped"));
        var stock=db.one("SELECT * FROM inventory WHERE part_id=?",PART);assertThat(stock.get("on_hand")).isEqualTo(8);assertThat(stock.get("reserved")).isEqualTo(0);
        assertThatThrownBy(()->orders.transition(shipping,"cancel",new Inputs.Decision("late"))).isInstanceOf(ApiError.class);
    }
    @Test void concurrentApprovalsCannotOverReserve() throws Exception {
        var c=create();Long first=proposal(repair(c),7),second=proposal(repair(c),7);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);List<Future<Boolean>> futures=new ArrayList<>();
            for(Long id:List.of(first,second)) futures.add(pool.submit(()->{login("quality");barrier.await();try {orders.transition(id,"approve",new Inputs.Decision("yes"));return true;}catch(ApiError e){return false;}finally{logout();}}));
            assertThat(futures.get(0).get() ^ futures.get(1).get()).isTrue();
        }
        assertThat(db.one("SELECT reserved FROM inventory WHERE part_id=?",PART).get("reserved")).isEqualTo(7);
    }
    @Test void draftHistoryCommentsAndConfirmedDashboard() {
        var c=create();Long repair=repair(c);var first=reports.draft(repair,new Inputs.Draft(0,content("MOTOR")));Long report=(Long)first.get("id");assertThat(count("MOTOR")).isZero();
        reports.confirm(report,new Inputs.Confirm(1,1));assertThat(count("MOTOR")).isEqualTo(1);
        login("quality");var comment=new Inputs.Comment(1,"본사 의견",UUID.randomUUID());reports.comment(report,comment);reports.comment(report,comment);
        login("tech1");reports.draft(repair,new Inputs.Draft(2,content("SW")));assertThat(count("MOTOR")).isEqualTo(1);assertThat(count("SW")).isZero();
        assertThatThrownBy(()->reports.draft(repair,new Inputs.Draft(2,content("MOTOR")))).isInstanceOf(ApiError.class);
        reports.confirm(report,new Inputs.Confirm(3,2));assertThat(count("MOTOR")).isZero();assertThat(count("SW")).isEqualTo(1);assertThat(reports.comments(report)).hasSize(1);
        assertThat(db.list("SELECT * FROM repair_report_revision WHERE report_id=?",report)).hasSize(2);assertThat(db.one("SELECT fault_domain,repair_case_count FROM fault_dashboard").get("fault_domain")).isEqualTo("SW");
    }
    @Test void concurrentReportVersionsOnlyOneWins() throws Exception {
        Long repair=repair(create());reports.draft(repair,new Inputs.Draft(0,content("MOTOR")));
        try(var pool=Executors.newFixedThreadPool(2)) {
            var barrier=new CyclicBarrier(2);List<Future<Boolean>> futures=new ArrayList<>();
            for(int n=0;n<2;n++) futures.add(pool.submit(()->{login("tech1");barrier.await();try{reports.draft(repair,new Inputs.Draft(1,content("SW")));return true;}catch(ApiError e){return false;}finally{logout();}}));
            assertThat(futures.get(0).get() ^ futures.get(1).get()).isTrue();
        }
    }
    @Test void sseReplayIsCommittedAndOrdered() {
        var c=create();events.receive(event(c,1,"DIAGNOSIS_PROGRESS",Map.of("stage","A")));events.receive(event(c,2,"DIAGNOSIS_PROGRESS",Map.of("stage","B")));
        var all=hub.replay(repair(c),0,100);long first=((Number)all.getFirst().get("id")).longValue();
        assertThat(hub.replay(repair(c),first,100)).hasSize(all.size()-1);assertThat(all).extracting(r->r.get("event_type")).contains("DIAGNOSIS_PROGRESS");
    }
    @Test void kafkaResultDeliveryAndDuplicateIdempotency() throws Exception {
        var c=create();String body=event(c,1,"DIAGNOSIS_COMPLETED",result());
        producer.send("diagnosis.events.v2",task(c).toString(),body).get(10,TimeUnit.SECONDS);producer.send("diagnosis.events.v2",task(c).toString(),body).get(10,TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(()->assertThat(db.one("SELECT status FROM diagnosis_job WHERE id=?",task(c)).get("status")).isEqualTo("SUCCEEDED"));
        assertThat(db.list("SELECT * FROM received_event WHERE job_id=?",task(c))).hasSize(1);
    }
    @Test void kafkaBoundedRetriesAndDlt() throws Exception {
        var c=create();String body=event(c,1,"DIAGNOSIS_PROGRESS",Map.of("stage","RETRY"));
        doThrow(new org.springframework.dao.TransientDataAccessResourceException("test outage")).when(events).receive(body);
        producer.send("diagnosis.events.v2",task(c).toString(),body).get(10,TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(45)).untilAsserted(()->assertThat(db.one("SELECT status FROM diagnosis_job WHERE id=?",task(c)).get("status")).isEqualTo("FAILED"));
        assertThat(db.one("SELECT retry_count FROM diagnosis_job WHERE id=?",task(c)).get("retry_count")).isEqualTo(3);verify(events,times(4)).receive(body);
    }
    @Autowired OutboxPublisher publisher;
    @Test void outboxReplayAndDuplicateCommandsHaveSingleBusinessEffect() throws Exception {
        var c=create();
        Map<String,Object> cfg=Map.of("bootstrap.servers",kafka.getBootstrapServers(),"group.id","outbox_event-test-"+UUID.randomUUID(),"auto.offset.reset","earliest","enable.auto.commit",false);
        try(var consumer=new org.apache.kafka.clients.consumer.KafkaConsumer<String,String>(cfg,new org.apache.kafka.common.serialization.StringDeserializer(),new org.apache.kafka.common.serialization.StringDeserializer())) {
            consumer.subscribe(List.of("diagnosis.commands.v2"));
            publisher.publish();db.update("UPDATE outbox_event SET published_at=NULL WHERE topic IS NOT NULL");publisher.publish();
            List<org.apache.kafka.clients.consumer.ConsumerRecord<String,String>> commands=new ArrayList<>();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(commands.size()<2 && System.nanoTime()<deadline) for(var record:consumer.poll(Duration.ofMillis(250))) if(record.key().equals(task(c).toString())) commands.add(record);
            assertThat(commands).hasSize(2);assertThat(commands.get(0).value()).isEqualTo(commands.get(1).value());
            var ai=new MockAi(producer);for(var record:commands) ai.command(record);
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertThat(db.one("SELECT status FROM diagnosis_job WHERE id=?",task(c)).get("status")).isEqualTo("SUCCEEDED"));
        assertThat(db.list("SELECT * FROM received_event WHERE job_id=?",task(c))).hasSize(4);
        var worker=new ProcurementWorker(db,new MockBusinessMcp(db),orders,manager);worker.run();worker.run();
        assertThat(db.list("SELECT * FROM purchase_order WHERE case_id=?",repair(c))).hasSize(1);
        assertThat(db.list("SELECT * FROM stock_snapshot WHERE case_id=?",repair(c))).hasSize(1);
    }
    @Test void sseIdsFollowCommitOrderForConcurrentWriters() throws Exception {
        var c=create();Long repair=repair(c);long initial=((Number)hub.replay(repair,0,100).getLast().get("id")).longValue();
        var inserted=new CountDownLatch(1);var release=new CountDownLatch(1);var secondStarted=new CountDownLatch(1);
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->tx.executeWithoutResult(status->{db.event(repair,"FIRST",Map.of());inserted.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}}));
            assertThat(inserted.await(5,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->tx.executeWithoutResult(status->{secondStarted.countDown();db.event(repair,"SECOND",Map.of());}));
            assertThat(secondStarted.await(5,TimeUnit.SECONDS)).isTrue();
            assertThat(hub.replay(repair,initial,100)).isEmpty();assertThat(second.isDone()).isFalse();
            release.countDown();first.get(5,TimeUnit.SECONDS);second.get(5,TimeUnit.SECONDS);
        } finally {release.countDown();}
        assertThat(hub.replay(repair,initial,100)).extracting(row->row.get("event_type")).containsExactly("FIRST","SECOND");
        assertThatThrownBy(()->tx.executeWithoutResult(status->{db.event(repair,"ROLLBACK",Map.of());throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);
        assertThat(hub.replay(repair,initial,100)).hasSize(2);
    }

    @Test void everyReadApiAndDocumentedAliasUsesCanonicalTables() throws Exception {
        var c=create();Long repair=repair(c),job=task(c);Long attachment=(Long)db.one("SELECT id FROM attachment WHERE case_id=?",repair).get("id");
        events.receive(event(c,1,"DIAGNOSIS_COMPLETED",result()));
        var draft=reports.draft(repair,new Inputs.Draft(0,content("MOTOR")));Long report=(Long)draft.get("id");Long order=proposal(repair,1);
        reports.confirm(report,new Inputs.Confirm(1,1));
        String[] urls={"/api/auth/csrf","/api/auth/me","/api/products?serial=ROBOT-SEOUL-001","/api/products?serialNumber=ROBOT-SEOUL-001","/api/repairs?page=0&size=10&status=CLOSED&serial=ROBOT-SEOUL-001","/api/repairs/"+repair,"/api/tasks/"+job,"/api/repairs/"+repair+"/diagnosis","/api/repairs/"+repair+"/diagnosis/tools","/api/attachments/"+attachment,"/api/parts","/api/parts/1/inventory","/api/parts/1/factories","/api/repairs/"+repair+"/procurement","/api/orders/"+order,"/api/repairs/"+repair+"/report","/api/reports/"+report,"/api/reports/"+report+"/history","/api/reports/"+report+"/comments","/api/dashboard?from=2020-01-01&to=2100-01-01","/api/dashboard/faults?from=2020-01-01&to=2100-01-01"};
        for(String url:urls) mvc.perform(get(url).with(user("tech1"))).andExpect(status().isOk());
        String[] internal={"/internal/tasks/"+job+"/input","/internal/attachments/"+attachment,"/internal/products?serial=ROBOT-SEOUL-001","/internal/products?serialNumber=ROBOT-SEOUL-001","/internal/parts","/internal/parts/1/inventory","/internal/parts/1/factories","/internal/parts/1/availability","/internal/confirmed-cases","/internal/fault-cases"};
        for(String url:internal) mvc.perform(get(url).header("Authorization","Bearer test-service-secret-at-least-32-characters")).andExpect(status().isOk());
        mvc.perform(get("/api/products?serial=ROBOT-SEOUL-001").with(user("tech1"))).andExpect(jsonPath("$.id").value("1")).andExpect(jsonPath("$.model_id").isString()).andExpect(jsonPath("$.serial_number").value("ROBOT-SEOUL-001"));
        assertThat(db.list("SELECT * FROM diagnosis_candidate WHERE job_id=?",job)).hasSize(1);assertThat(db.list("SELECT * FROM diagnosis_recommended_part WHERE job_id=?",job)).hasSize(1);
    }
    @Test void everyMutationApiUsesCanonicalTablesAndAuthorization() throws Exception {
        var intake=new Inputs.Intake(PRODUCT,"HTTP 원문",List.of("E01"),"1.0",Map.of("customId",123));
        var data=new MockMultipartFile("data","","application/json",Json.write(intake).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var image=new MockMultipartFile("files","wheel.png","image/png",photo().getBytes());
        String created=mvc.perform(multipart("/api/repairs").file(data).file(image).with(user("tech1")).with(csrf())).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        var c=Json.object(created);c.put("repairId",Json.id(c.get("repairId")));c.put("taskId",Json.id(c.get("taskId")));Long repair=repair(c);
        for(String path:List.of("diagnosis","diagnoses")) mvc.perform(post("/api/repairs/"+repair+"/"+path).header("Idempotency-Key",UUID.randomUUID()).with(user("tech1")).with(csrf())).andExpect(status().isAccepted());
        UUID requestKey=UUID.randomUUID();events.receive(event(c,1,"PHOTO_REQUESTED",Map.of("requestKey",requestKey,"round",1,"bodyPart","wheel","description","photo","checkpoint","cp")));Long request=photoId(requestKey);
        String uploaded=mvc.perform(multipart("/api/repairs/"+repair+"/attachments").file(photo()).param("requestId",request.toString()).with(user("tech1")).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();Long photo=Json.id(Json.object(uploaded).get("attachmentId"));UUID submission=UUID.randomUUID();
        mvc.perform(post("/api/photo-requests/"+request+"/submit").with(user("tech1")).with(csrf()).contentType("application/json").content(Json.write(new Inputs.PhotoSubmission(submission,List.of(photo))))).andExpect(status().isAccepted());
        mvc.perform(post("/api/repairs/"+repair+"/diagnosis/resume").with(user("tech1")).with(csrf()).contentType("application/json").content(Json.write(new Inputs.Resume(request,submission,List.of(photo))))).andExpect(status().isAccepted());
        events.receive(event(c,1,"DIAGNOSIS_COMPLETED",result()));
        mvc.perform(post("/api/repairs/"+repair+"/procurement/retry").with(user("tech1")).with(csrf())).andExpect(status().isAccepted());
        for(String action:List.of("approve","reject","cancel")) {
            var proposal=new Inputs.Proposal(List.of(new Inputs.Item(PART,1,Inputs.Source.WAREHOUSE,WAREHOUSE,null)));
            String response=mvc.perform(post("/api/repairs/"+repair+"/orders").with(user("tech1")).with(csrf()).contentType("application/json").content(Json.write(proposal))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();Long id=Json.id(Json.object(response).get("id"));
            mvc.perform(post("/api/orders/"+id+"/"+action).with(user(action.equals("cancel")?"tech1":"quality")).with(csrf()).contentType("application/json").content("{\"reason\":\"HTTP decision\"}")).andExpect(status().isOk());
            if(action.equals("approve")) for(String next:List.of("ship","receive")) mvc.perform(post("/api/orders/"+id+"/"+next).with(user("tech1")).with(csrf()).contentType("application/json").content("{\"reason\":\"local transition\"}")).andExpect(status().isOk());
        }
        String saved=mvc.perform(put("/api/repairs/"+repair+"/report/draft").with(user("tech1")).with(csrf()).contentType("application/json").content(Json.write(new Inputs.Draft(0,content("MOTOR"))))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();Long report=Json.id(Json.object(saved).get("id"));
        mvc.perform(post("/api/reports/"+report+"/confirm").with(user("tech1")).with(csrf()).contentType("application/json").content(Json.write(new Inputs.Confirm(1,1)))).andExpect(status().isOk());
        mvc.perform(post("/api/reports/"+report+"/comments").with(user("quality")).with(csrf()).contentType("application/json").content(Json.write(new Inputs.Comment(1,"HTTP HQ comment",UUID.randomUUID())))).andExpect(status().isOk());
        mvc.perform(post("/api/repairs/"+repair+"/report").with(user("tech1")).with(csrf()).contentType("application/json").content(Json.write(new Inputs.Draft(2,content("SW"))))).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").with(user("tech1")).with(csrf())).andExpect(status().isNoContent());
    }
    @Test void noFaultReportAndDisabledAccountFollowDocumentedPolicy() throws Exception {
        var c=create();
        db.update("DELETE FROM model_part WHERE model_id=1 AND part_id=1");
        try {assertThatThrownBy(()->reports.draft(repair(c),new Inputs.Draft(0,new Inputs.ReportContent("cause",List.of(new Inputs.Fault("MOTOR",COMPONENT,null)),"action","opinion",true,Inputs.Acceptance.PARTIAL)))).isInstanceOf(ApiError.class);}
        finally {db.update("INSERT INTO model_part VALUES (1,1) ON CONFLICT DO NOTHING");}
        assertThatThrownBy(()->reports.draft(repair(c),new Inputs.Draft(0,content("PCB")))).isInstanceOf(ApiError.class);
        var noFault=new Inputs.ReportContent("No issue",List.of(),"inspection","opinion",false,Inputs.Acceptance.NOT_AVAILABLE,Inputs.Outcome.NO_FAULT_FOUND);
        var report=reports.saveAndConfirm(repair(c),new Inputs.Draft(0,noFault));assertThat(report.get("outcome")).isEqualTo("NO_FAULT_FOUND");assertThat(count("MOTOR")).isZero();
        db.update("UPDATE app_user SET active=false WHERE email='tech2'");
        try {mvc.perform(get("/api/auth/me").with(user("tech2"))).andExpect(status().isUnauthorized());}
        finally {db.update("UPDATE app_user SET active=true WHERE email='tech2'");}
    }
    @Test void swaggerUiAndPackagedContractArePublicWhileBusinessApisRemainProtected() throws Exception {
        SecurityContextHolder.clearContext();
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/swagger-ui/index.html"));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("swagger-ui-bundle.js")));
        mvc.perform(get("/swagger-ui/swagger-initializer.js")).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.containsString("requestInterceptor")));
        mvc.perform(get("/webjars/swagger-ui/5.32.1/swagger-ui.css")).andExpect(status().isOk());
        mvc.perform(get("/webjars/swagger-ui/5.32.1/swagger-ui-bundle.js")).andExpect(status().isOk());
        var served=mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith("application/json")).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(Json.object(served)).isEqualTo(Json.object(java.nio.file.Files.readString(java.nio.file.Path.of("docs/openapi.json"))));
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/parts")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").param("username","tech1").param("password","TestPassword-Only123!")).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").with(csrf()).param("username","tech1").param("password","TestPassword-Only123!")).andExpect(status().isOk());
    }
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping routes;
    @Test void openApiListsEveryApplicationRoute() throws Exception {
        Set<String> expected=new TreeSet<>(List.of("post /api/auth/login","post /api/auth/logout"));
        routes.getHandlerMethods().forEach((mapping,method)->{
            for(String path:mapping.getPatternValues()) if(path.startsWith("/api/") || path.startsWith("/internal/")) {
                var expanded=new ArrayList<String>();
                if(path.contains("{action:")) for(String action:List.of("approve","reject","cancel","ship","receive")) expanded.add(path.replaceAll("\\{action:[^}]+}",action));else expanded.add(path);
                for(var verb:mapping.getMethodsCondition().getMethods()) for(String item:expanded) expected.add(verb.name().toLowerCase()+" "+item);
            }
        });
        var openapi=Json.object(java.nio.file.Files.readString(java.nio.file.Path.of("docs/openapi.json")));var paths=(Map<?,?>)openapi.get("paths");Set<String> actual=new TreeSet<>();paths.forEach((path,methods)->((Map<?,?>)methods).keySet().forEach(verb->actual.add(verb+" "+path)));
        assertThat(actual).isEqualTo(expected);
    }

    @Test void taskLookupReturnsExactHistoricalJob() throws Exception {
        var c=create();Long historical=db.insert("INSERT INTO diagnosis_job(case_id,request_key,workflow_version,status,created_at) VALUES (?,?,'history','FAILED',now()-interval '1 day')",repair(c),UUID.randomUUID());
        mvc.perform(get("/api/tasks/"+historical).with(user("tech1"))).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(historical.toString())).andExpect(jsonPath("$.status").value("FAILED"));
        mvc.perform(get("/api/tasks/"+historical).with(user("tech2"))).andExpect(status().isForbidden());
    }

    @Autowired PhotoQualityRepository photoRepository;@Autowired PhotoQualityService photoQuality;
    @Test void disablingQualityPreservesAutomaticStartAndExplicitlyLogsBypassForPendingPhotos() {
        var c=create();Long id=(Long)db.one("SELECT id FROM attachment WHERE case_id=?",repair(c)).get("id");assertThat(c.get("taskId")).isNotNull();
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        tx.executeWithoutResult(status->{photoRepository.enqueue(id,null,1);photoQuality.freeze(repair(c),task(c),2,List.of(id),List.of());});
        assertThat(db.one("SELECT quality_gate FROM diagnosis_input_attachment WHERE job_id=? AND input_revision=2",task(c)).get("quality_gate")).isEqualTo("DISABLED");
        assertThat(db.one("SELECT status,verdict FROM photo_quality_result WHERE attachment_id=?",id).get("status")).isEqualTo("PENDING");
        assertThat(db.one("SELECT verdict FROM photo_quality_result WHERE attachment_id=?",id).get("verdict")).isNull();
    }

}
