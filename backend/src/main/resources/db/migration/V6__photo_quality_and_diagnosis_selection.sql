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
