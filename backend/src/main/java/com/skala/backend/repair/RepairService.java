package com.skala.backend.repair;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.beans.factory.annotation.Value;
import java.time.Instant;
import java.util.*;

@Service
public class RepairService {
    final Store db;final Access access;final LocalFiles files;final Catalog catalog;final String commands;
    public RepairService(Store db,Access access,LocalFiles files,Catalog catalog,@Value("${app.commands-topic}") String commands) {this.db=db;this.access=access;this.files=files;this.catalog=catalog;this.commands=commands;}
    @Transactional public Map<String,Object> create(Inputs.Intake data,List<MultipartFile> uploads) {return create(data,uploads,null);}
    // [통합 패치] 사진마다 부위(target)를 받는다. 프론트 PCB/MECHANICAL → DB 규칙 PCB/MOTOR
    @Transactional public Map<String,Object> create(Inputs.Intake data,List<MultipartFile> uploads,List<String> targets) {
        access.technician();var user=access.user();
        var product=db.one("SELECT serial_number FROM product WHERE id=?",data.productId());var snapshot=catalog.product((String)product.get("serial_number"));
        Long id=db.insert("INSERT INTO repair_case(case_number,center_id,product_id,technician_id,symptom,error_codes,firmware_version,user_input,product_snapshot) VALUES (?,?,?,?,?,?::jsonb,?,?::jsonb,?::jsonb)",
            "R-"+UUID.randomUUID(),user.centerId,data.productId(),user.id,data.symptoms(),Json.write(data.errorCodes()),data.firmware(),Json.raw(data.userInput()),Json.write(snapshot));
        if(uploads==null || uploads.isEmpty() || uploads.size()>10) throw ApiError.bad("INITIAL_FILES_REQUIRED_MAX_10");
        for(int i=0;i<uploads.size();i++) addFile(id,null,uploads.get(i),targets==null||i>=targets.size()?null:targets.get(i));
        db.audit(user.email,"REPAIR_CREATED",id,data);return start(id,null);
    }
    @Transactional public Map<String,Object> start(Long repair) {return start(repair,null);}
    @Transactional public Map<String,Object> start(Long repair,UUID key) {
        access.technician();access.repair(repair);db.one("SELECT id FROM repair_case WHERE id=? FOR UPDATE",repair);
        if(key!=null) {var keyed=db.list("SELECT * FROM diagnosis_job WHERE request_key=?",key);if(!keyed.isEmpty() && !repair.equals(keyed.getFirst().get("case_id"))) throw ApiError.conflict("IDEMPOTENCY_KEY_REUSED");}
        var existing=db.list("SELECT id,status FROM diagnosis_job WHERE case_id=? ORDER BY created_at DESC",repair);
        if(!existing.isEmpty()) return Map.of("repairId",repair,"taskId",existing.getFirst().get("id"),"status",existing.getFirst().get("status"));
        if(db.list("SELECT id FROM attachment WHERE case_id=?",repair).isEmpty()) throw ApiError.conflict("ATTACHMENTS_REQUIRED");
        Long task=db.insert("INSERT INTO diagnosis_job(case_id,request_key,workflow_version) VALUES (?,?,'repair-v2')",repair,key==null?UUID.randomUUID():key);
        db.update("UPDATE repair_case SET status='DIAGNOSING',version=version+1 WHERE id=?",repair);command(repair,task,"DIAGNOSIS_REQUESTED",null,List.of());
        db.event(repair,"DIAGNOSIS_QUEUED",Map.of("taskId",task));return Map.of("repairId",repair,"taskId",task,"status","QUEUED");
    }
    @Transactional public Map<String,Object> upload(Long repair,Long request,MultipartFile file) {return upload(repair,request,file,null);}
    @Transactional public Map<String,Object> upload(Long repair,Long request,MultipartFile file,String target) {
        access.technician();access.repair(repair);var task=db.one("SELECT * FROM diagnosis_job WHERE case_id=? FOR UPDATE",repair);
        if(!task.get("status").equals("WAITING_INPUT") || request==null) throw ApiError.conflict("NOT_WAITING_INPUT");
        var req=db.one("SELECT * FROM photo_request WHERE id=? AND job_id=?",request,task.get("id"));
        if(!req.get("status").equals("OPEN")) throw ApiError.conflict("REQUEST_ALREADY_FULFILLED");return addFile(repair,request,file,target);
    }
    static String target(String raw) {
        if(raw==null) return "GENERAL";
        return switch(raw.trim().toUpperCase()) {case "PCB"->"PCB";case "MECHANICAL","MOTOR"->"MOTOR";case "SW"->"SW";default->"GENERAL";};
    }
    Map<String,Object> addFile(Long repair,Long request,MultipartFile file) {return addFile(repair,request,file,null);}
    Map<String,Object> addFile(Long repair,Long request,MultipartFile file,String target) {
        var saved=files.save(file);String name=Optional.ofNullable(file.getOriginalFilename()).orElse("attachment");if(name.length()>255) name=name.substring(name.length()-255);
        String kind=saved.mime().startsWith("image/")?"IMAGE":saved.mime().equals("text/plain")?"LOG":"DOCUMENT";
        Long id=db.insert("INSERT INTO attachment(case_id,request_id,uploader_id,kind,target,object_key,original_name,mime_type,byte_size,sha256) VALUES (?,?,?,?,?,?,?,?,?,?)",repair,request,access.user().id,kind,target(target),saved.key(),name,saved.mime(),saved.size(),saved.hash());
        db.audit(access.user().email,"ATTACHMENT_CREATED",id,Map.of("attachmentId",id));return Map.of("attachmentId",id,"mimeType",saved.mime(),"size",saved.size(),"sha256",saved.hash());
    }
    @Transactional public Map<String,Object> resume(Long repair,Inputs.Resume input) {
        access.technician();access.repair(repair);var task=db.one("SELECT * FROM diagnosis_job WHERE case_id=? FOR UPDATE",repair);
        var request=db.one("SELECT * FROM photo_request WHERE id=? AND job_id=? FOR UPDATE",input.requestId(),task.get("id"));
        if(request.get("status").equals("SUBMITTED")) {
            if(!input.submissionKey().equals(request.get("submission_key"))) throw ApiError.conflict("REQUEST_ALREADY_FULFILLED");
            var prior=db.one("SELECT payload FROM outbox_event WHERE job_id=? AND event_type='DIAGNOSIS_RESUMED' AND payload->'payload'->>'requestId'=?",task.get("id"),input.requestId().toString());
            var body=(Map<?,?>)prior.get("payload");var p=(Map<?,?>)body.get("payload");
            if(!new HashSet<>(((List<?>)p.get("addedAttachmentIds")).stream().map(Object::toString).toList()).equals(new HashSet<>(input.attachmentIds().stream().map(Object::toString).toList()))) throw ApiError.conflict("IDEMPOTENCY_KEY_REUSED");
            return Map.of("repairId",repair,"taskId",task.get("id"),"inputRevision",body.get("inputRevision"));
        }
        if(!task.get("status").equals("WAITING_INPUT") || !request.get("status").equals("OPEN")) throw ApiError.conflict("NOT_WAITING_INPUT");
        if(new HashSet<>(input.attachmentIds()).size()!=input.attachmentIds().size()) throw ApiError.bad("DUPLICATE_ATTACHMENT");
        for(Long id:input.attachmentIds()) {
            var attachment=db.one("SELECT kind FROM attachment WHERE id=? AND case_id=? AND request_id=?",id,repair,input.requestId());
            if(!attachment.get("kind").equals("IMAGE")) throw ApiError.bad("PHOTO_REQUIRED");
        }
        db.update("UPDATE photo_request SET status='SUBMITTED',submitted_at=now(),submission_key=? WHERE id=?",input.submissionKey(),input.requestId());
        db.update("UPDATE diagnosis_job SET status='QUEUED',input_revision=input_revision+1,execution_id=?,last_sequence=0,version=version+1,updated_at=now() WHERE id=?",UUID.randomUUID(),task.get("id"));
        db.update("UPDATE repair_case SET status='DIAGNOSING',version=version+1 WHERE id=?",repair);
        command(repair,(Long)task.get("id"),"DIAGNOSIS_RESUMED",input.requestId(),input.attachmentIds());db.event(repair,"DIAGNOSIS_RESUMED",Map.of("taskId",task.get("id")));
        return Map.of("repairId",repair,"taskId",task.get("id"),"inputRevision",Json.number(task.get("input_revision"))+1);
    }
    // [통합 추가] 백엔드에 DIAGNOSED → REPAIRING 전이가 없어 프론트 리포트 화면(TECHNICIAN_REVIEW=REPAIRING)이 열리지 않던 문제.
    // 확인·반려 모두 기사가 수리를 시작하는 것(AI 수용 여부는 리포트 aiAcceptance에 남는다). 본사 검토 요청은 MANUAL_REVIEW, 본사 회신은 다시 REPAIRING.
    @Transactional public void approval(Long repair,Inputs.Approval input) {
        boolean manual=input.target().equals("MANUAL_REVIEW");
        if(manual) access.manager(); else access.technician();
        access.repair(repair);var row=db.one("SELECT status FROM repair_case WHERE id=? FOR UPDATE",repair);
        String from=manual?"MANUAL_REVIEW":"DIAGNOSED",to=input.action().equals("REQUEST_HQ_REVIEW")?"MANUAL_REVIEW":"REPAIRING";
        if(manual!=input.action().equals("RETURN_TO_TECHNICIAN")) throw ApiError.bad("APPROVAL_ACTION_TARGET_MISMATCH");
        if(!row.get("status").equals(from)) throw ApiError.conflict("NOT_"+from);
        db.update("UPDATE repair_case SET status=?,version=version+1 WHERE id=?",to,repair);
        var data=new LinkedHashMap<String,Object>();data.put("target",input.target());data.put("action",input.action());data.put("comment",input.comment());
        db.event(repair,"APPROVAL_"+input.action(),data);db.audit(access.user().email,"APPROVAL_"+input.action(),repair,data);
    }
    public Map<String,Object> analysisInput(Long repair,Long task) {
        var result=new LinkedHashMap<String,Object>();result.put("repair",db.one("SELECT * FROM repair_case WHERE id=?",repair));
        result.put("task",db.one("SELECT * FROM diagnosis_job WHERE id=? AND case_id=?",task,repair));
        var attachments=db.list("SELECT id,request_id,kind,target,mime_type,byte_size,sha256 FROM attachment WHERE case_id=? ORDER BY created_at,id",repair);
        for(var a:attachments) a.put("downloadPath","/internal/attachments/"+a.get("id"));result.put("attachments",attachments);return result;
    }
    @Transactional public void retryProcurement(Long repair) {
        access.technician();access.repair(repair);var job=db.one("SELECT * FROM procurement_job WHERE case_id=? FOR UPDATE",repair);
        if(Boolean.TRUE.equals(job.get("done")) || (job.get("lease_until")!=null && Instant.parse(String.valueOf(job.get("lease_until"))).isAfter(Instant.now()))) throw ApiError.conflict("PROCUREMENT_NOT_RETRYABLE");
        db.update("UPDATE procurement_job SET attempts=0,next_attempt_at=now(),lease_until=NULL WHERE case_id=?",repair);
        db.update("UPDATE repair_case SET procurement_status='PENDING' WHERE id=?",repair);db.audit(access.user().email,"PROCUREMENT_RETRIED",repair,Map.of());
    }
    void command(Long repair,Long task,String type,Long request,List<Long> added) {
        var t=db.one("SELECT * FROM diagnosis_job WHERE id=?",task);var payload=analysisInput(repair,task);
        payload.put("attachmentIds",((List<?>)payload.get("attachments")).stream().map(a->((Map<?,?>)a).get("id")).toList());payload.put("addedAttachmentIds",added);payload.put("requestId",request);
        payload.put("requestKey",request==null?null:db.one("SELECT request_key FROM photo_request WHERE id=?",request).get("request_key"));payload.put("round",t.get("photo_request_count"));payload.put("checkpoint",t.get("checkpoint_ref"));
        UUID event=UUID.randomUUID();var body=new LinkedHashMap<String,Object>();body.put("eventId",event);body.put("eventType",type);body.put("schemaVersion",2);body.put("occurredAt",Instant.now().toString());body.put("repairId",repair);body.put("taskId",task);body.put("correlationId",((Map<?,?>)payload.get("repair")).get("correlation_id"));body.put("inputRevision",t.get("input_revision"));body.put("executionId",t.get("execution_id"));body.put("sequence",0);body.put("payload",payload);
        db.update("INSERT INTO outbox_event(case_id,job_id,event_key,event_type,topic,payload) VALUES (?,?,?,?,?,?::jsonb)",repair,task,event,type,commands,Json.write(body));
    }
}
