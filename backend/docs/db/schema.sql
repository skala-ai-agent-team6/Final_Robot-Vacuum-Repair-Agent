-- Canonical fresh-install schema, aligned with Flyway V4. Do not run on an existing database.
BEGIN;
-- PostgreSQL public schema snapshot after Flyway V4/V5.

CREATE TABLE service_center (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 code varchar(40) NOT NULL UNIQUE, name text NOT NULL, region text NOT NULL,
 address text, phone text
);
CREATE TABLE app_user (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 email text NOT NULL UNIQUE, password_hash text NOT NULL, name text NOT NULL,
 role text NOT NULL CHECK(role IN ('TECHNICIAN','QUALITY_MANAGER')),
 center_id bigint REFERENCES service_center,
 active boolean NOT NULL DEFAULT true,
 CHECK(role <> 'TECHNICIAN' OR center_id IS NOT NULL)
);
CREATE TABLE product_model (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 model_code text NOT NULL UNIQUE, name text NOT NULL
);
CREATE TABLE product (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 serial_number text NOT NULL UNIQUE, model_id bigint NOT NULL REFERENCES product_model,
 manufactured_on date
);
CREATE TABLE part (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 part_number text NOT NULL UNIQUE, name text NOT NULL,
 category text NOT NULL CHECK(category IN ('PCB','MOTOR','OTHER'))
);
CREATE TABLE model_part (
 model_id bigint REFERENCES product_model, part_id bigint REFERENCES part,
 PRIMARY KEY(model_id,part_id)
);
CREATE TABLE factory (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 name text NOT NULL, country text NOT NULL, region text NOT NULL,
 address text NOT NULL, phone text NOT NULL
);
CREATE TABLE part_supplier (
 part_id bigint REFERENCES part, factory_id bigint REFERENCES factory,
 lead_days integer CHECK(lead_days>=0), PRIMARY KEY(part_id,factory_id)
);
CREATE TABLE part_lot (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 part_id bigint NOT NULL REFERENCES part, factory_id bigint NOT NULL REFERENCES factory,
 lot_number text NOT NULL, manufactured_on date,
 UNIQUE(part_id,factory_id,lot_number), UNIQUE(id,part_id)
);
CREATE TABLE product_component (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 product_id bigint NOT NULL REFERENCES product, part_id bigint NOT NULL REFERENCES part,
 lot_id bigint, slot_code text NOT NULL, component_serial text,
 installed_at timestamptz NOT NULL DEFAULT now(), removed_at timestamptz,
 FOREIGN KEY(lot_id,part_id) REFERENCES part_lot(id,part_id),
 CHECK(removed_at IS NULL OR removed_at>=installed_at), UNIQUE(id,product_id)
);
CREATE UNIQUE INDEX component_active_slot ON product_component(product_id,slot_code) WHERE removed_at IS NULL;
CREATE TABLE repair_case (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 case_number text NOT NULL UNIQUE, product_id bigint NOT NULL REFERENCES product,
 center_id bigint NOT NULL REFERENCES service_center,
 technician_id bigint NOT NULL REFERENCES app_user,
 symptom text NOT NULL, error_codes jsonb NOT NULL DEFAULT '[]', firmware_version text,
 status text NOT NULL DEFAULT 'RECEIVED' CHECK(status IN
 ('RECEIVED','DIAGNOSING','WAITING_INPUT','DIAGNOSED','MANUAL_REVIEW','REPAIRING','CLOSED','CANCELLED')),
 created_at timestamptz NOT NULL DEFAULT now(), closed_at timestamptz,
 version bigint NOT NULL DEFAULT 0, UNIQUE(id,product_id),
 CHECK(closed_at IS NULL OR closed_at>=created_at)
);
CREATE TABLE diagnosis_job (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 case_id bigint NOT NULL REFERENCES repair_case,
 request_key uuid NOT NULL UNIQUE,
 status text NOT NULL DEFAULT 'QUEUED' CHECK(status IN
 ('QUEUED','RUNNING','WAITING_INPUT','SUCCEEDED','FAILED','CANCELLED')),
 photo_request_count smallint NOT NULL DEFAULT 0 CHECK(photo_request_count BETWEEN 0 AND 3),
 retry_count integer NOT NULL DEFAULT 0 CHECK(retry_count>=0),
 workflow_version text NOT NULL, checkpoint_ref text,
 lease_until timestamptz, error_code text, error_message text,
 created_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz,
 version bigint NOT NULL DEFAULT 0, UNIQUE(id,case_id)
);
CREATE UNIQUE INDEX one_active_job ON diagnosis_job(case_id)
 WHERE status IN ('QUEUED','RUNNING','WAITING_INPUT');
CREATE TABLE photo_request (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 job_id bigint NOT NULL REFERENCES diagnosis_job,
 round_no smallint NOT NULL CHECK(round_no BETWEEN 1 AND 3),
 instruction text NOT NULL, target text NOT NULL,
 status text NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','SUBMITTED','EXPIRED','CANCELLED')),
 created_at timestamptz NOT NULL DEFAULT now(), submitted_at timestamptz,
 UNIQUE(job_id,round_no), UNIQUE(id,job_id)
);
CREATE TABLE attachment (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 case_id bigint NOT NULL REFERENCES repair_case,
 request_id bigint REFERENCES photo_request,
 uploader_id bigint NOT NULL REFERENCES app_user,
 kind text NOT NULL CHECK(kind IN ('IMAGE','LOG','DOCUMENT')),
 target text CHECK(target IN ('PCB','MOTOR','SW','GENERAL')),
 object_key text NOT NULL UNIQUE, mime_type text NOT NULL,
 byte_size bigint NOT NULL CHECK(byte_size>0), sha256 char(64) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(id,case_id)
);
CREATE TABLE agent_execution (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 job_id bigint NOT NULL REFERENCES diagnosis_job,
 call_key uuid NOT NULL UNIQUE, agent_type text NOT NULL CHECK(agent_type IN ('PCB','MOTOR','SW','RAG','BUSINESS')),
 mcp_server text NOT NULL, tool_name text NOT NULL, model_version text,
 status text NOT NULL CHECK(status IN ('RUNNING','SUCCEEDED','FAILED')),
 attempt integer NOT NULL DEFAULT 1 CHECK(attempt>0),
 input_payload jsonb NOT NULL, output_payload jsonb, error_message text,
 started_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz,
 UNIQUE(id,job_id)
);
CREATE TABLE diagnosis_candidate (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 job_id bigint NOT NULL REFERENCES diagnosis_job,
 execution_id bigint, part_id bigint REFERENCES part,
 rank integer NOT NULL CHECK(rank>0), cause_code text NOT NULL,
 cause_description text NOT NULL, confidence numeric(5,4) CHECK(confidence BETWEEN 0 AND 1),
 repair_guide text NOT NULL, recommended_quantity integer CHECK(recommended_quantity>0),
 FOREIGN KEY(execution_id,job_id) REFERENCES agent_execution(id,job_id),
 UNIQUE(job_id,rank), UNIQUE(id,job_id)
);
CREATE TABLE diagnosis_evidence (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 candidate_id bigint NOT NULL REFERENCES diagnosis_candidate,
 attachment_id bigint REFERENCES attachment,
 source_uri text, source_version text, excerpt text, annotation jsonb,
 CHECK(attachment_id IS NOT NULL OR source_uri IS NOT NULL OR excerpt IS NOT NULL)
);
CREATE TABLE repair_report (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 case_id bigint NOT NULL UNIQUE REFERENCES repair_case,
 product_id bigint NOT NULL, author_id bigint NOT NULL REFERENCES app_user,
 diagnosis_job_id bigint, outcome text NOT NULL CHECK(outcome IN ('FIXED','NOT_FIXED','NO_FAULT_FOUND')),
 ai_assessment text NOT NULL CHECK(ai_assessment IN ('ACCEPTED','PARTIAL','REJECTED','NOT_AVAILABLE')),
 comment text NOT NULL, action_taken text NOT NULL,
 confirmed_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(case_id,product_id) REFERENCES repair_case(id,product_id),
 FOREIGN KEY(diagnosis_job_id,case_id) REFERENCES diagnosis_job(id,case_id),
 UNIQUE(id,product_id)
);
CREATE TABLE confirmed_fault (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 report_id bigint NOT NULL, product_id bigint NOT NULL,
 component_id bigint, fault_domain text NOT NULL CHECK(fault_domain IN ('PCB','MOTOR','SW','OTHER')),
 cause_code text NOT NULL, description text NOT NULL,
 FOREIGN KEY(report_id,product_id) REFERENCES repair_report(id,product_id),
 FOREIGN KEY(component_id,product_id) REFERENCES product_component(id,product_id)
);
CREATE TABLE warehouse (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 name text NOT NULL, region text NOT NULL, address text NOT NULL, phone text
);
CREATE TABLE inventory (
 warehouse_id bigint REFERENCES warehouse, part_id bigint REFERENCES part,
 on_hand integer NOT NULL DEFAULT 0 CHECK(on_hand>=0),
 reserved integer NOT NULL DEFAULT 0 CHECK(reserved BETWEEN 0 AND on_hand),
 version bigint NOT NULL DEFAULT 0, PRIMARY KEY(warehouse_id,part_id)
);
CREATE TABLE purchase_order (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 case_id bigint NOT NULL REFERENCES repair_case,
 request_key uuid NOT NULL UNIQUE, requested_by bigint NOT NULL REFERENCES app_user,
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','APPROVED','REJECTED','ORDERED','RECEIVED','CANCELLED')),
 decided_by bigint REFERENCES app_user, decision_note text, decided_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(), version bigint NOT NULL DEFAULT 0,
 CHECK((status IN ('PENDING','CANCELLED')) OR (decided_by IS NOT NULL AND decided_at IS NOT NULL))
);
CREATE TABLE purchase_order_item (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 order_id bigint NOT NULL REFERENCES purchase_order,
 part_id bigint NOT NULL REFERENCES part, quantity integer NOT NULL CHECK(quantity>0),
 warehouse_id bigint REFERENCES warehouse, factory_id bigint REFERENCES factory,
 CHECK((warehouse_id IS NOT NULL)::int+(factory_id IS NOT NULL)::int=1),
 FOREIGN KEY(warehouse_id,part_id) REFERENCES inventory(warehouse_id,part_id),
 FOREIGN KEY(part_id,factory_id) REFERENCES part_supplier(part_id,factory_id)
);
-- SSE와 큐 발행을 동일 업무 트랜잭션에 기록. published_at은 큐 발행 결과이며 SSE 수신확인이 아님.
CREATE TABLE outbox_event (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 case_id bigint NOT NULL REFERENCES repair_case,
 event_key uuid NOT NULL UNIQUE, event_type text NOT NULL,
 payload jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 published_at timestamptz, publish_attempts integer NOT NULL DEFAULT 0 CHECK(publish_attempts>=0)
);
CREATE TABLE audit_log (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 actor_user_id bigint REFERENCES app_user, actor_service text,
 action text NOT NULL, entity_type text NOT NULL, entity_id bigint NOT NULL,
 request_key uuid, detail jsonb NOT NULL DEFAULT '{}', created_at timestamptz NOT NULL DEFAULT now(),
 CHECK(actor_user_id IS NOT NULL OR actor_service IS NOT NULL)
);
CREATE INDEX repair_center_created ON repair_case(center_id,created_at DESC);
CREATE INDEX job_case ON diagnosis_job(case_id,created_at DESC);
CREATE INDEX attachment_case ON attachment(case_id);
CREATE INDEX execution_job ON agent_execution(job_id);
CREATE INDEX evidence_candidate ON diagnosis_evidence(candidate_id);
CREATE INDEX fault_report ON confirmed_fault(report_id);
CREATE INDEX component_lot ON product_component(lot_id);
CREATE INDEX order_case ON purchase_order(case_id);
CREATE INDEX order_item_order ON purchase_order_item(order_id);
CREATE INDEX event_replay ON outbox_event(case_id,id);
CREATE INDEX event_unpublished ON outbox_event(id) WHERE published_at IS NULL;

-- Latest requirements beyond the original DB draft.
ALTER TABLE product ADD COLUMN information jsonb NOT NULL DEFAULT '{}';
ALTER TABLE repair_case ADD COLUMN user_input jsonb NOT NULL DEFAULT '{}', ADD COLUMN product_snapshot jsonb NOT NULL DEFAULT '{}',
 ADD COLUMN procurement_status text NOT NULL DEFAULT 'NOT_STARTED' CHECK(procurement_status IN ('NOT_STARTED','PENDING','SUCCEEDED','FAILED')),
 ADD COLUMN correlation_id uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE diagnosis_job ADD COLUMN input_revision integer NOT NULL DEFAULT 1 CHECK(input_revision>0),
 ADD COLUMN execution_id uuid NOT NULL DEFAULT gen_random_uuid(), ADD COLUMN last_sequence bigint NOT NULL DEFAULT 0,
 ADD COLUMN stage text, ADD COLUMN result jsonb, ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE photo_request ADD COLUMN request_key uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE, ADD COLUMN submission_key uuid;
ALTER TABLE attachment ADD COLUMN original_name text NOT NULL DEFAULT 'attachment', ADD CONSTRAINT attachment_max_size CHECK(byte_size<=10485760);
CREATE TABLE received_event(event_id uuid PRIMARY KEY, job_id bigint NOT NULL REFERENCES diagnosis_job, body jsonb NOT NULL,
 disposition text NOT NULL, received_at timestamptz NOT NULL DEFAULT now());
ALTER TABLE agent_execution ADD COLUMN event_id uuid UNIQUE REFERENCES received_event, ADD COLUMN execution_ref uuid,
 ADD COLUMN tool_version text NOT NULL DEFAULT 'UNKNOWN';
CREATE TABLE diagnosis_recommended_part(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, job_id bigint NOT NULL REFERENCES diagnosis_job,
 part_id bigint NOT NULL REFERENCES part, quantity integer NOT NULL CHECK(quantity>0));
ALTER TABLE diagnosis_evidence ADD COLUMN details jsonb NOT NULL DEFAULT '{}';
ALTER TABLE repair_report ALTER COLUMN confirmed_at DROP NOT NULL, ALTER COLUMN confirmed_at DROP DEFAULT,
 ADD COLUMN version bigint NOT NULL DEFAULT 0, ADD COLUMN latest_revision integer NOT NULL DEFAULT 0, ADD COLUMN confirmed_revision integer,
 ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
CREATE TABLE repair_report_revision(report_id bigint NOT NULL REFERENCES repair_report, revision integer NOT NULL CHECK(revision>0),
 content jsonb NOT NULL, author_id bigint NOT NULL REFERENCES app_user, created_at timestamptz NOT NULL DEFAULT now(), confirmed_at timestamptz,
 PRIMARY KEY(report_id,revision));
ALTER TABLE repair_report ADD CONSTRAINT confirmed_revision_fk FOREIGN KEY(id,confirmed_revision) REFERENCES repair_report_revision(report_id,revision);
ALTER TABLE confirmed_fault ADD COLUMN revision integer NOT NULL, ADD COLUMN ordinal integer NOT NULL,
 ADD COLUMN part_id bigint REFERENCES part, ADD COLUMN factory_id bigint REFERENCES factory,
 ADD COLUMN factory_name text NOT NULL DEFAULT 'UNKNOWN', ADD COLUMN production_region text NOT NULL DEFAULT 'UNKNOWN',
 ADD CONSTRAINT fault_revision_fk FOREIGN KEY(report_id,revision) REFERENCES repair_report_revision,
 ADD CONSTRAINT fault_revision_ordinal UNIQUE(report_id,revision,ordinal);
CREATE TABLE report_comment(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, report_id bigint NOT NULL, revision integer NOT NULL,
 author_id bigint NOT NULL REFERENCES app_user, body varchar(4000) NOT NULL CHECK(length(trim(body))>0), request_key uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), FOREIGN KEY(report_id,revision) REFERENCES repair_report_revision,
 UNIQUE(report_id,request_key));
ALTER TABLE purchase_order_item ADD COLUMN source text GENERATED ALWAYS AS (CASE WHEN warehouse_id IS NOT NULL THEN 'WAREHOUSE' ELSE 'FACTORY' END) STORED;
ALTER TABLE outbox_event ADD COLUMN job_id bigint REFERENCES diagnosis_job, ADD COLUMN topic text,
 ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now(), ADD COLUMN lease_until timestamptz;
CREATE TABLE procurement_job(case_id bigint PRIMARY KEY REFERENCES repair_case, attempts integer NOT NULL DEFAULT 0,
 next_attempt_at timestamptz NOT NULL DEFAULT now(), lease_until timestamptz, last_error text, done boolean NOT NULL DEFAULT false);
CREATE TABLE stock_snapshot(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, case_id bigint NOT NULL REFERENCES repair_case,
 body jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE legacy_id_map(entity text NOT NULL, old_id uuid NOT NULL, new_id bigint NOT NULL, PRIMARY KEY(entity,old_id),UNIQUE(entity,new_id));
CREATE INDEX comment_report_date ON report_comment(report_id,created_at);
CREATE INDEX procurement_pending ON procurement_job(next_attempt_at) WHERE done=false;
CREATE INDEX recommended_job ON diagnosis_recommended_part(job_id);
CREATE VIEW fault_dashboard AS
SELECT date_trunc('month',rr.confirmed_at AT TIME ZONE 'Asia/Seoul') AS month,
 c.center_id,x.part_id,x.factory_id,x.production_region,x.fault_domain,count(DISTINCT c.id) AS repair_case_count
FROM confirmed_fault x JOIN repair_report r ON r.id=x.report_id AND r.confirmed_revision=x.revision
JOIN repair_report_revision rr ON rr.report_id=r.id AND rr.revision=r.confirmed_revision
JOIN repair_case c ON c.id=r.case_id GROUP BY 1,2,3,4,5,6;

ALTER TABLE attachment ADD COLUMN quality_required boolean NOT NULL DEFAULT false,
 ADD COLUMN latest_quality_result_id bigint,
 ADD COLUMN use_status text NOT NULL DEFAULT 'AVAILABLE' CHECK(use_status IN ('AVAILABLE','EXCLUDED','REPLACED')),
 ADD COLUMN replaced_by_id bigint REFERENCES attachment;
CREATE TABLE photo_quality_result (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 attachment_id bigint NOT NULL REFERENCES attachment,
 previous_result_id bigint UNIQUE REFERENCES photo_quality_result,
 attempt integer NOT NULL CHECK(attempt>0),
 status text NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','RUNNING','COMPLETED','ERROR')),
 verdict text CHECK(verdict IN ('PASS','WARNING')),
 checker_version text NOT NULL, policy_version text NOT NULL,
 original_width integer,original_height integer,inspection_width integer,inspection_height integer,
 mean_brightness double precision,dark_ratio double precision,bright_ratio double precision,sharpness double precision,
 issues jsonb NOT NULL DEFAULT '[]',
 created_at timestamptz NOT NULL DEFAULT now(),started_at timestamptz,completed_at timestamptz,duration_ms bigint,
 failure_code text,run_token uuid,lease_until timestamptz,recovery_count integer NOT NULL DEFAULT 0,
 UNIQUE(attachment_id,attempt),UNIQUE(id,attachment_id),
 CHECK((status='COMPLETED')=(verdict IS NOT NULL)),
 CHECK(mean_brightness IS NULL OR mean_brightness BETWEEN 0 AND 255),
 CHECK(dark_ratio IS NULL OR dark_ratio BETWEEN 0 AND 1),CHECK(bright_ratio IS NULL OR bright_ratio BETWEEN 0 AND 1),
 CHECK(sharpness IS NULL OR sharpness>=0),CHECK(duration_ms IS NULL OR duration_ms>=0)
);
ALTER TABLE attachment ADD CONSTRAINT latest_photo_quality_fk FOREIGN KEY(latest_quality_result_id,id) REFERENCES photo_quality_result(id,attachment_id);
CREATE INDEX photo_quality_pending ON photo_quality_result(created_at,id) WHERE status='PENDING';
CREATE INDEX photo_quality_recovery ON photo_quality_result(lease_until) WHERE status='RUNNING';
CREATE TABLE photo_quality_confirmation (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 attachment_id bigint NOT NULL, result_id bigint NOT NULL,
 confirmed_by bigint NOT NULL REFERENCES app_user,confirmed_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(result_id,attachment_id) REFERENCES photo_quality_result(id,attachment_id),UNIQUE(result_id,confirmed_by)
);
CREATE TABLE diagnosis_input_attachment (
 job_id bigint NOT NULL REFERENCES diagnosis_job,input_revision integer NOT NULL CHECK(input_revision>0),
 attachment_id bigint NOT NULL REFERENCES attachment,
 quality_result_id bigint,warning_confirmation_id bigint REFERENCES photo_quality_confirmation,
 added boolean NOT NULL DEFAULT false,
 quality_gate text NOT NULL DEFAULT 'LEGACY' CHECK(quality_gate IN ('CHECKED','LEGACY','DISABLED','NOT_APPLICABLE')),
 PRIMARY KEY(job_id,input_revision,attachment_id),
 FOREIGN KEY(quality_result_id,attachment_id) REFERENCES photo_quality_result(id,attachment_id)
);
-- Freeze the actual historical input where available, without starting any new diagnosis.
INSERT INTO diagnosis_input_attachment(job_id,input_revision,attachment_id,added)
SELECT o.job_id,(o.payload->>'inputRevision')::integer,a.id,
 coalesce((o.payload->'payload'->'addedAttachmentIds') @> jsonb_build_array(a.id::text),false)
FROM outbox_event o JOIN diagnosis_job j ON j.id=o.job_id
JOIN LATERAL jsonb_array_elements_text(o.payload->'payload'->'attachmentIds') selected(id) ON true
JOIN attachment a ON a.id=CASE WHEN selected.id ~ '^[1-9][0-9]*$' THEN selected.id::bigint END AND a.case_id=j.case_id
WHERE o.topic IS NOT NULL AND o.event_type IN ('DIAGNOSIS_REQUESTED','DIAGNOSIS_RESUMED')
ON CONFLICT DO NOTHING;

COMMIT;
