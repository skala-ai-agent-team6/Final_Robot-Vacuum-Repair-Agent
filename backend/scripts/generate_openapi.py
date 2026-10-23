#!/usr/bin/env python3
"""Regenerate the checked-in OpenAPI document; no third-party Python dependencies."""
import json
from pathlib import Path

def ref(name): return {'$ref': '#/components/schemas/' + name}
def array(item): return {'type': 'array', 'items': item}
def obj(properties, required=None, **extra):
    return {'type': 'object', 'properties': properties, 'required': list(properties) if required is None else required, **extra}
def string(**extra): return {'type': 'string', **extra}
def integer(**extra): return {'type': 'integer', **extra}
def nullable(schema): return {'anyOf': [schema, {'type': 'null'}]}
uid = string(pattern='^[1-9][0-9]*$', description='PostgreSQL bigint ID encoded as a decimal string')
key = string(format='uuid')
date = string(format='date-time')
free = {'type': 'object', 'additionalProperties': True}
boolean = {'type': 'boolean'}
S = {}
S['Error'] = obj({'code': string(), 'status': integer()})
S['Csrf'] = obj({'headerName': string(), 'token': string()})
S['User'] = obj({'id': uid, 'username': string(), 'role': string(enum=['TECHNICIAN','QUALITY_MANAGER']), 'centerId': nullable(uid)})
S['Intake'] = obj({'productId': uid, 'symptoms': string(minLength=1,maxLength=10000), 'errorCodes': array(string(minLength=1,maxLength=100)), 'firmware': string(minLength=1,maxLength=100), 'userInput': free})
S['Accepted'] = obj({'repairId': uid,'taskId':uid,'status':string()}, ['repairId','taskId'])
S['Resume'] = obj({'requestId':uid,'submissionKey':key,'attachmentIds':array(uid)})
S['ResumeAccepted'] = obj({'repairId':uid,'taskId':uid,'inputRevision':integer(minimum=2)})
S['Uploaded'] = obj({'attachmentId':uid,'mimeType':string(),'size':integer(format='int64'),'sha256':string()})
S['Attachment'] = obj({'id':uid,'request_id':nullable(uid),'original_name':string(),'mime_type':string(),'size':integer(format='int64'),'sha256':string(),'created_at':date})
S['Task'] = obj({'id':uid,'repair_id':uid,'status':string(enum=['QUEUED','RUNNING','WAITING_INPUT','SUCCEEDED','FAILED','CANCELLED']), 'input_revision':integer(),'execution_id':uid,'last_sequence':integer(format='int64'),'photo_round':integer(minimum=0,maximum=3),'server_retries':integer(),'stage':nullable(string()),'error':nullable(string()),'checkpoint':nullable(string()),'result':nullable(ref('DiagnosisResult')),'created_at':date,'updated_at':date})
S['Candidate'] = obj({'rank':integer(minimum=1),'cause':string(),'confidence':{'type':'number','minimum':0,'maximum':1},'explanation':string()})
S['RecommendedPart'] = obj({'partId':uid,'quantity':integer(minimum=1)})
S['Evidence'] = obj({'type':string(),'attachmentId':uid,'annotation':free,'citation':string()}, ['type'])
S['DiagnosisResult'] = obj({'summary':string(),'modelVersion':string(),'repairMethod':string(),'candidates':array(ref('Candidate')),'recommendedParts':array(ref('RecommendedPart')),'evidence':array(ref('Evidence'))})
S['PhotoRequest'] = obj({'id':uid,'task_id':uid,'round':integer(),'body_part':string(),'description':string(),'fulfilled_at':nullable(date),'submission_key':nullable(uid)})
S['Component'] = obj({'id':uid,'product_id':uid,'part_id':uid,'lot_id':nullable(uid),'installed_at':date,'removed_at':nullable(date),'lot_code':nullable(string()),'factory_id':nullable(uid),'factory_name':nullable(string()),'region':nullable(string())})
S['Product'] = obj({'id':uid,'serial':string(),'model':string(),'information':free,'components':array(ref('Component'))})
S['Repair'] = obj({'id':uid,'center_id':uid,'product_id':uid,'created_by':uid,'symptoms':string(),'error_codes':array(string()),'firmware':string(),'user_input':free,'product_snapshot':free,'status':string(enum=['RECEIVED','DIAGNOSING','MANUAL_REVIEW','DIAGNOSED','REPAIRED']),'procurement_status':string(enum=['NOT_STARTED','PENDING','SUCCEEDED','FAILED']),'created_at':date})
S['RepairDetail'] = {'allOf':[ref('Repair'),obj({'attachments':array(ref('Attachment')),'tasks':array(ref('Task')),'photoRequests':array(ref('PhotoRequest')),'reports':array(ref('ReportHeader')),'orders':array(ref('OrderHeader'))})]}
S['RepairPage'] = obj({'page':integer(),'size':integer(),'total':integer(format='int64'),'items':array(ref('Repair'))})
S['Fault'] = obj({'area':string(minLength=1,maxLength=100),'componentId':nullable(uid),'partId':nullable(uid)}, ['area'])
S['ReportContent'] = obj({'actualCause':string(minLength=1,maxLength=10000),'faults':array(ref('Fault')),'actualAction':string(minLength=1,maxLength=10000),'technicianOpinion':string(maxLength=10000),'repairSuccessful':boolean,'aiAcceptance':string(enum=['ACCEPTED','PARTIAL','REJECTED'])})
S['Draft'] = obj({'version':integer(format='int64',minimum=0),'content':ref('ReportContent')})
S['Confirm'] = obj({'version':integer(format='int64',minimum=0),'revision':integer(minimum=1)})
S['ReportHeader'] = obj({'id':uid,'repair_id':uid,'version':integer(format='int64'),'latest_revision':integer(),'confirmed_revision':nullable(integer()),'created_at':date})
S['Revision'] = obj({'report_id':uid,'revision':integer(),'content':ref('ReportContent'),'author_id':uid,'created_at':date,'confirmed_at':nullable(date)})
S['Report'] = {'allOf':[ref('ReportHeader'),obj({'revisions':array(ref('Revision')),'comments':array(ref('Comment'))})]}
S['CommentInput'] = obj({'revision':integer(minimum=1),'body':string(minLength=1,maxLength=4000),'requestKey':key})
S['Comment'] = obj({'id':uid,'report_id':uid,'revision':integer(),'author_id':uid,'body':string(),'request_key':uid,'created_at':date})
S['OrderItemInput'] = obj({'partId':uid,'quantity':integer(minimum=1),'source':string(enum=['WAREHOUSE','FACTORY']),'warehouseId':nullable(uid),'factoryId':nullable(uid)},['partId','quantity','source'])
S['Proposal'] = obj({'items':array(ref('OrderItemInput'))})
S['Decision'] = obj({'reason':string(minLength=1,maxLength=2000)})
S['OrderItem'] = obj({'id':uid,'order_id':uid,'part_id':uid,'quantity':integer(minimum=1),'source':string(enum=['WAREHOUSE','FACTORY']),'warehouse_id':nullable(uid),'factory_id':nullable(uid)})
S['OrderHeader'] = obj({'id':uid,'repair_id':uid,'status':string(enum=['PROPOSED','APPROVED','REJECTED','CANCELLED','SHIPPED']),'created_by':nullable(uid),'decided_by':nullable(uid),'decided_at':nullable(date),'reason':nullable(string()),'created_at':date})
S['Order'] = {'allOf':[ref('OrderHeader'),obj({'items':array(ref('OrderItem'))})]}
S['Part'] = obj({'id':uid,'name':string(),'factory_id':nullable(uid)})
S['Factory'] = obj({'id':uid,'name':string(),'region':string(),'contact':string()})
S['Stock'] = obj({'warehouse_id':uid,'part_id':uid,'on_hand':integer(),'reserved':integer(),'available':integer(),'warehouse_name':string()})
S['Procurement'] = obj({'status':string(),'snapshots':array(obj({'id':uid,'repair_id':uid,'body':free,'created_at':date})),'orders':array(ref('OrderHeader'))})
S['ToolExecution'] = obj({'event_id':uid,'task_id':uid,'execution_id':uid,'details':free})
S['DashboardRow'] = obj({'dimension':string(),'count':integer(format='int64')})
S['ConfirmedCase'] = obj({'report_id':uid,'repair_id':uid,'confirmed_revision':integer(),'content':ref('ReportContent'),'confirmed_at':date})
S['AnalysisInput'] = obj({'repair':ref('Repair'),'task':ref('Task'),'attachments':array(obj({'id':uid,'request_id':nullable(uid),'mime_type':string(),'size':integer(),'sha256':string(),'downloadPath':string()}))})

# Canonical docs/db schema vocabulary and persisted types.
S['User']=obj({'id':uid,'username':string(),'email':string(),'name':string(),'active':boolean,'role':string(enum=['TECHNICIAN','QUALITY_MANAGER']),'centerId':nullable(uid)})
S['Attachment']=obj({'id':uid,'request_id':nullable(uid),'kind':string(enum=['IMAGE','LOG','DOCUMENT']),'target':nullable(string()),'original_name':string(),'mime_type':string(),'byte_size':integer(format='int64'),'sha256':string(),'created_at':date})
S['Task']=obj({'id':uid,'case_id':uid,'request_key':key,'workflow_version':string(),'status':string(enum=['QUEUED','RUNNING','WAITING_INPUT','SUCCEEDED','FAILED','CANCELLED']),'input_revision':integer(),'execution_id':key,'last_sequence':integer(format='int64'),'photo_request_count':integer(minimum=0,maximum=3),'retry_count':integer(),'stage':nullable(string()),'checkpoint_ref':nullable(string()),'error_code':nullable(string()),'error_message':nullable(string()),'lease_until':nullable(date),'result':nullable(ref('DiagnosisResult')),'created_at':date,'updated_at':date,'finished_at':nullable(date),'version':integer(format='int64'),'candidates':array(free),'evidence':array(free),'recommendedParts':array(free)},['id','case_id','request_key','workflow_version','status','input_revision','execution_id','last_sequence','photo_request_count','retry_count','stage','checkpoint_ref','error_code','error_message','lease_until','result','created_at','updated_at','finished_at','version'])
S['PhotoRequest']=obj({'id':uid,'job_id':uid,'round_no':integer(),'instruction':string(),'target':string(),'status':string(enum=['OPEN','SUBMITTED','EXPIRED','CANCELLED']),'created_at':date,'submitted_at':nullable(date),'request_key':key,'submission_key':nullable(key)})
S['Component']=obj({'id':uid,'product_id':uid,'part_id':uid,'lot_id':nullable(uid),'slot_code':string(),'component_serial':nullable(string()),'installed_at':date,'removed_at':nullable(date),'lot_number':nullable(string()),'factory_id':nullable(uid),'factory_name':nullable(string()),'region':nullable(string())})
S['Product']=obj({'id':uid,'serial_number':string(),'model_id':uid,'model_code':string(),'model_name':string(),'manufactured_on':nullable(string(format='date')),'information':free,'components':array(ref('Component')),'compatibleParts':array(ref('Part'))})
S['Repair']=obj({'id':uid,'case_number':string(),'center_id':uid,'product_id':uid,'technician_id':uid,'symptom':string(),'error_codes':array(string()),'firmware_version':nullable(string()),'user_input':free,'product_snapshot':free,'status':string(enum=['RECEIVED','DIAGNOSING','WAITING_INPUT','DIAGNOSED','MANUAL_REVIEW','REPAIRING','CLOSED','CANCELLED']),'procurement_status':string(enum=['NOT_STARTED','PENDING','SUCCEEDED','FAILED']),'created_at':date,'closed_at':nullable(date),'version':integer(format='int64'),'correlation_id':key})
S['Fault']['properties']['area']=string(enum=['PCB','MOTOR','SW','OTHER'])
S['ReportContent']['properties']['aiAcceptance']=string(enum=['ACCEPTED','PARTIAL','REJECTED','NOT_AVAILABLE'])
S['ReportContent']['properties']['outcome']=nullable(string(enum=['FIXED','NOT_FIXED','NO_FAULT_FOUND']))
S['ReportHeader']=obj({'id':uid,'case_id':uid,'product_id':uid,'author_id':uid,'diagnosis_job_id':nullable(uid),'outcome':string(enum=['FIXED','NOT_FIXED','NO_FAULT_FOUND']),'ai_assessment':string(),'comment':string(),'action_taken':string(),'confirmed_at':nullable(date),'version':integer(format='int64'),'latest_revision':integer(),'confirmed_revision':nullable(integer()),'created_at':date})
S['OrderHeader']=obj({'id':uid,'case_id':uid,'request_key':key,'requested_by':uid,'status':string(enum=['PENDING','APPROVED','REJECTED','ORDERED','RECEIVED','CANCELLED']),'decided_by':nullable(uid),'decided_at':nullable(date),'decision_note':nullable(string()),'created_at':date,'version':integer(format='int64')})
S['Part']=obj({'id':uid,'part_number':string(),'name':string(),'category':string(enum=['PCB','MOTOR','OTHER'])})
S['Factory']=obj({'id':uid,'name':string(),'country':string(),'region':string(),'address':string(),'phone':string(),'lead_days':nullable(integer())})
S['Stock']=obj({'warehouse_id':uid,'part_id':uid,'on_hand':integer(),'reserved':integer(),'available':integer(),'warehouse_name':string(),'region':string(),'address':string(),'phone':nullable(string()),'version':integer(format='int64')})
S['Procurement']['properties']['snapshots']=array(obj({'id':uid,'case_id':uid,'body':free,'created_at':date}))
S['ToolExecution']=obj({'id':uid,'job_id':uid,'call_key':key,'agent_type':string(),'mcp_server':string(),'tool_name':string(),'tool_version':string(),'model_version':nullable(string()),'status':string(),'attempt':integer(),'input_payload':free,'output_payload':nullable(free),'error_message':nullable(string()),'started_at':date,'finished_at':nullable(date),'event_id':nullable(key),'execution_ref':nullable(key)})
S['ConfirmedCase']=obj({'report_id':uid,'case_id':uid,'confirmed_revision':integer(),'content':ref('ReportContent'),'confirmed_at':date})
S['AnalysisInput']=obj({'repair':ref('Repair'),'task':ref('Task'),'attachments':array(obj({'id':uid,'request_id':nullable(uid),'kind':string(),'target':nullable(string()),'mime_type':string(),'byte_size':integer(),'sha256':string(),'downloadPath':string()}))})
S['PhotoSubmission']=obj({'submissionKey':key,'attachmentIds':array(uid)})
S['TaskDetail']={'allOf':[ref('Task'),obj({'photoRequests':array(ref('PhotoRequest'))})]}
S['Availability']=obj({'part':ref('Part'),'inventory':array(ref('Stock')),'factories':array(ref('Factory'))})
paths = {}
def query(name, schema=string(), required=False): return {'in':'query','name':name,'required':required,'schema':schema}
def add(path, method, summary, response=free, body=None, params=None, code=200, media='application/json', internal=False):
    import re
    parameters = [{'in':'path','name':name,'required':True,'schema':uid} for name in re.findall(r'{(\w+)}',path)] + (params or [])
    op={'summary':summary,'operationId':method+'_'+re.sub(r'[^A-Za-z0-9]','_',path),'parameters':parameters,'responses':{str(code):{'description':'Success','content':{media:{'schema':response}}}}}
    for status in [400,401,403,404,409,500]: op['responses'][str(status)]={'description':'See docs/API.md error policy','content':{'application/json':{'schema':ref('Error')}}}
    if body is not None: op['requestBody']={'required':True,'content':{'application/json':{'schema':body}}}
    op['security']=[{'ServiceToken':[]}] if internal else [{'SessionCookie':[]}]
    paths.setdefault(path,{})[method]=op
    return op
add('/api/auth/csrf','get','CSRF token (also before login)',ref('Csrf'))['security']=[]
op=add('/api/auth/login','post','Form login; requires CSRF header',obj({'authenticated':boolean}))
op['security']=[];op['requestBody']={'required':True,'content':{'application/x-www-form-urlencoded':{'schema':obj({'username':string(),'password':string(format='password')})}}}
add('/api/auth/me','get','Current authenticated user',ref('User'))
op=add('/api/auth/logout','post','Logout; requires CSRF header',code=204);op['responses']['204']={'description':'Logged out'}
add('/api/products','get','Product and installed parts by serial',ref('Product'),params=[query('serial',required=True)])
op=add('/api/repairs','post','TECHNICIAN: persist intake, files, task and outbox atomically',ref('Accepted'),code=202)
op['requestBody']={'required':True,'content':{'multipart/form-data':{'schema':obj({'data':ref('Intake'),'files':array(string(format='binary'))}),'encoding':{'data':{'contentType':'application/json'},'files':{'contentType':'image/png, image/jpeg, application/pdf, text/plain'}}}}}
add('/api/repairs','get','Center-scoped paginated repairs',ref('RepairPage'),params=[query('page',integer(minimum=0,default=0)),query('size',integer(minimum=1,maximum=100,default=20)),query('status'),query('serial')])
add('/api/repairs/{id}','get','Repair detail with tasks, attachments, photo requests',ref('RepairDetail'))
add('/api/repairs/{id}/diagnosis','post','TECHNICIAN: idempotent task request (returns existing task)',ref('Accepted'),code=202)
add('/api/repairs/{id}/diagnosis','get','Latest task status and integrated result',ref('Task'))
add('/api/repairs/{id}/diagnosis/tools','get','Separate per-tool execution history',array(ref('ToolExecution')))
op=add('/api/repairs/{id}/attachments','post','TECHNICIAN: upload photo/log/document for unfulfilled request',ref('Uploaded'),params=[query('requestId',uid,True)])
op['requestBody']={'required':True,'content':{'multipart/form-data':{'schema':obj({'file':string(format='binary')})}}}
add('/api/attachments/{id}','get','Authenticated attachment download',string(format='binary'),media='application/octet-stream')
add('/api/repairs/{id}/diagnosis/resume','post','TECHNICIAN: submit saved photos; same task, next input revision',ref('ResumeAccepted'),ref('Resume'),code=202)
op=add('/api/repairs/{id}/events','get','Commit-only SSE stream; 15s heartbeat; native EventSource cookies',string(),media='text/event-stream',params=[{'in':'header','name':'Last-Event-ID','required':False,'schema':integer(format='int64',minimum=0)}])
add('/api/parts','get','Parts catalog',array(ref('Part')))
add('/api/parts/{id}/inventory','get','Warehouse stock, reserved and available',array(ref('Stock')))
add('/api/parts/{id}/factories','get','Part production factory and contact',array(ref('Factory')))
add('/api/repairs/{id}/procurement','get','Procurement status, saved snapshots and proposals',ref('Procurement'))
add('/api/repairs/{id}/procurement/retry','post','TECHNICIAN: retry unsuccessful procurement',obj({'repairId':uid}),code=202)
add('/api/repairs/{id}/orders','post','TECHNICIAN: manual multi-item proposal independent of AI recommendation',ref('Order'),ref('Proposal'))
add('/api/orders/{id}','get','Order with actual order items',ref('Order'))
for action,role in [('approve','QUALITY_MANAGER'),('reject','QUALITY_MANAGER'),('cancel','TECHNICIAN'),('ship','TECHNICIAN'),('receive','TECHNICIAN')]:
    add('/api/orders/{id}/'+action,'post',role+': '+action+' local order',ref('Order'),ref('Decision'))
add('/api/repairs/{id}/report','get','Report header, all immutable revisions and independent HQ comments',ref('Report'))
add('/api/repairs/{id}/report/draft','put','TECHNICIAN: save revision with optimistic version',ref('Report'),ref('Draft'))
add('/api/reports/{id}','get','Report header, revisions and comments',ref('Report'))
add('/api/reports/{id}/history','get','Report revision history (descending)',array(ref('Revision')))
add('/api/reports/{id}/confirm','post','TECHNICIAN: confirm latest revision with version; update statistics',ref('Report'),ref('Confirm'))
add('/api/reports/{id}/comments','get','Independent HQ comments (ascending)',array(ref('Comment')))
add('/api/reports/{id}/comments','post','QUALITY_MANAGER: save idempotent revision-specific comment',ref('Comment'),ref('CommentInput'))
add('/api/dashboard','get','Latest confirmed revision; distinct repairs; Seoul confirmation date [from,to)',array(ref('DashboardRow')),params=[query('from',string(format='date'),True),query('to',string(format='date'),True),query('groupBy',string(enum=['PERIOD','PART','CENTER','FACTORY','REGION','AREA'],default='PERIOD'))])
add('/internal/tasks/{id}/input','get','AI service: persisted analysis source',ref('AnalysisInput'),internal=True)
add('/internal/attachments/{id}','get','AI service: download source attachment',string(format='binary'),media='application/octet-stream',internal=True)
add('/internal/products','get','Business MCP: product data owner lookup',ref('Product'),params=[query('serial',required=True)],internal=True)
add('/internal/parts','get','Business MCP: parts',array(ref('Part')),internal=True)
add('/internal/parts/{id}/inventory','get','Business MCP: stock; no outbound call',array(ref('Stock')),internal=True)
add('/internal/parts/{id}/factories','get','Business MCP: factories; no outbound call',array(ref('Factory')),internal=True)
add('/internal/confirmed-cases','get','RAG service: latest confirmed cases, deterministic pagination',array(ref('ConfirmedCase')),params=[query('page',integer(default=0)),query('size',integer(default=100,maximum=500))],internal=True)

add('/api/repairs/{id}/diagnoses','post','Documented DB draft alias; optional Idempotency-Key UUID',ref('Accepted'),code=202)
for name in ['diagnosis','diagnoses']:
    paths['/api/repairs/{id}/'+name]['post']['parameters'].append({'in':'header','name':'Idempotency-Key','required':False,'schema':key})
add('/api/tasks/{id}','get','Center-scoped task and photo requests',ref('TaskDetail'))
add('/api/photo-requests/{id}/submit','post','Submit saved photos for this request and resume same task',ref('ResumeAccepted'),ref('PhotoSubmission'),code=202)
add('/api/repairs/{id}/report','post','Save draft revision and confirm atomically using optimistic version',ref('Report'),ref('Draft'))
import copy
paths['/api/dashboard/faults']=copy.deepcopy(paths['/api/dashboard'])
paths['/api/dashboard/faults']['get']['operationId']='get_api_dashboard_faults'
paths['/internal/fault-cases']=copy.deepcopy(paths['/internal/confirmed-cases'])
paths['/internal/fault-cases']['get']['operationId']='get_internal_fault_cases'
add('/internal/parts/{id}/availability','get','MCP: combined catalog, warehouses and supplier factories',ref('Availability'),internal=True)
for route in ['/api/products','/internal/products']:
    paths[route]['get']['parameters']=[query('serial'),query('serialNumber')]
    paths[route]['get']['description']='One of serial or serialNumber is required.'

S['DiagnosisSelection']=obj({'attachmentIds':array(uid)})
S['QualityResult']=obj({'id':uid,'attachment_id':uid,'previous_result_id':nullable(uid),'attempt':integer(),'status':string(enum=['PENDING','RUNNING','COMPLETED','ERROR']),'verdict':nullable(string(enum=['PASS','WARNING'])),'checker_version':string(),'policy_version':string(),'original_width':nullable(integer()),'original_height':nullable(integer()),'inspection_width':nullable(integer()),'inspection_height':nullable(integer()),'mean_brightness':nullable({'type':'number'}),'dark_ratio':nullable({'type':'number'}),'bright_ratio':nullable({'type':'number'}),'sharpness':nullable({'type':'number'}),'issues':array(string()),'guidance':array(obj({'code':string(),'message':string()})),'created_at':date,'started_at':nullable(date),'completed_at':nullable(date),'duration_ms':nullable(integer()),'failure_code':nullable(string()),'confirmedByCurrentUser':boolean})
S['PhotoQuality']=obj({'attachmentId':uid,'enabled':boolean,'required':boolean,'useStatus':string(enum=['AVAILABLE','EXCLUDED','REPLACED']),'replacedByAttachmentId':nullable(uid),'status':string(enum=['PENDING','RUNNING','COMPLETED','ERROR','LEGACY_UNCHECKED','NOT_APPLICABLE']),'latest':nullable(ref('QualityResult')),'history':array(ref('QualityResult'))})
S['Uploaded']['properties']['quality']=ref('PhotoQuality');S['Uploaded']['required'].append('quality')
S['Accepted']['properties']['taskId']=nullable(uid)
S['Accepted']['properties']['attachments']=array(ref('Uploaded'))
S['Accepted']['properties']['requiresDiagnosisRequest']=boolean
S['Attachment']['properties'].update({'quality_required':boolean,'latest_quality_result_id':nullable(uid),'use_status':string(),'replaced_by_id':nullable(uid)})
S['Resume']['properties']['excludedAttachmentIds']=array(uid)
S['PhotoSubmission']['properties']['excludedAttachmentIds']=array(uid)
for route in ['/api/repairs/{id}/diagnosis','/api/repairs/{id}/diagnoses']:
    paths[route]['post']['requestBody']={'required':False,'description':'Required when quality is enabled and no existing task: select attachment IDs after quality results.','content':{'application/json':{'schema':ref('DiagnosisSelection')}}}
paths['/api/repairs/{id}/attachments']['post']['parameters']=[dict(p,required=False) if p['name']=='requestId' else p for p in paths['/api/repairs/{id}/attachments']['post']['parameters']]
add('/api/attachments/{id}/quality','get','Current quality result, guidance, acknowledgment and history',ref('PhotoQuality'))
add('/api/attachments/{id}/quality/acknowledgment','post','TECHNICIAN: acknowledge current WARNING result only',ref('PhotoQuality'),obj({'resultId':uid}))
add('/api/attachments/{id}/quality/retry','post','TECHNICIAN: retry ERROR or changed policy, bounded and idempotent',ref('PhotoQuality'),obj({'resultId':nullable(uid)}))
add('/api/attachments/{id}/selection','put','TECHNICIAN: exclude or replace a photo for future input revisions',ref('PhotoQuality'),obj({'excluded':boolean,'replacedByAttachmentId':nullable(uid)},['excluded']))
# Preserve integrated-backend approval and per-photo target fields.
S['Approval']=obj({'target':string(enum=['DIAGNOSIS','MANUAL_REVIEW']),'action':string(enum=['APPROVE','REJECT','REQUEST_HQ_REVIEW','RETURN_TO_TECHNICIAN']),'comment':nullable(string(maxLength=4000))},['target','action'])
add('/api/repairs/{id}/approval','post','Confirm diagnosis or request/return HQ review',ref('RepairDetail'),ref('Approval'))
intakeSchema=paths['/api/repairs']['post']['requestBody']['content']['multipart/form-data']['schema']
intakeSchema['properties']['targets']=array(string(description='Per-file target: PCB, MECHANICAL/MOTOR, SW, GENERAL'))
paths['/api/repairs/{id}/attachments']['post']['parameters'].append(query('target',string(),False))

for path, methods in paths.items():
    for method, operation in methods.items():
        if method in ['post','put'] and not path.startswith('/internal/'):
            operation['parameters'].append({'in':'header','name':'X-CSRF-TOKEN','required':True,'schema':string(),'description':'GET /api/auth/csrf; refresh after successful login'})
# Swagger UI grouping and executable examples for every documented operation.
def sample(schema, depth=0):
    if depth > 12: return None
    if '$ref' in schema: return sample(S[schema['$ref'].rsplit('/',1)[-1]],depth+1)
    if 'example' in schema: return schema['example']
    if 'allOf' in schema:
        result={}
        for item in schema['allOf']: result.update(sample(item,depth+1) or {})
        return result
    if 'anyOf' in schema: return sample(next(x for x in schema['anyOf'] if x.get('type')!='null'),depth+1)
    if 'enum' in schema: return schema['enum'][0]
    if 'default' in schema: return schema['default']
    kind=schema.get('type')
    if kind=='object': return {k:sample(v,depth+1) for k,v in schema.get('properties',{}).items()}
    if kind=='array': return [sample(schema['items'],depth+1)]
    if kind=='boolean': return True
    if kind in ['integer','number']: return schema.get('minimum',1)
    if schema.get('pattern')==uid['pattern']: return '1'
    return {'uuid':'00000000-0000-4000-8000-000000000001','date':'2026-10-22','date-time':'2026-10-22T09:00:00+09:00','binary':'','password':'실제 계정 비밀번호 입력'}.get(schema.get('format'),'예시')

S['Intake']['example']={'productId':'1','symptoms':'청소 중 바퀴가 멈춥니다.','errorCodes':['E01'],'firmware':'1.2','userInput':{}}
S['DiagnosisSelection']['example']={'attachmentIds':['1']}
for path, methods in paths.items():
    for verb, operation in methods.items():
        if path.startswith('/internal/'): tag='내부 서비스'
        elif '/auth/' in path: tag='인증'
        elif '/quality' in path or '/selection' in path: tag='사진 품질'
        elif '/reports/' in path or '/report' in path: tag='수리 리포트'
        elif '/orders' in path or '/procurement' in path: tag='발주·조달'
        elif '/dashboard' in path: tag='통계'
        elif '/parts' in path or '/products' in path: tag='제품·부품'
        else: tag='접수·진단'
        operation['tags']=[tag]
        operation['description']=operation['summary']+'\n\n'+('내부 서비스 Bearer 토큰이 필요합니다.' if path.startswith('/internal/') else '로그인 세션과 기존 센터별 접근 권한을 적용합니다. 쓰기 요청의 CSRF 헤더는 이 Swagger UI에서 자동으로 설정합니다.')
        for parameter in operation.get('parameters',[]):
            if parameter['name']!='X-CSRF-TOKEN': parameter['example']=sample(parameter['schema'])
            else: parameter['description']='Swagger UI가 요청 직전 최신 CSRF 토큰을 자동으로 가져옵니다. 직접 호출할 때는 /api/auth/csrf에서 조회하세요.';parameter['required']=False
        for media, content in operation.get('requestBody',{}).get('content',{}).items():
            if media!='multipart/form-data': content['example']=sample(content['schema'])
        for code, response in operation['responses'].items():
            for media, content in response.get('content',{}).items():
                if media=='application/json': content['example']=({'code':'FORBIDDEN_OR_CSRF' if code=='403' else 'API_ERROR','status':int(code)} if int(code)>=400 else sample(content['schema']))
        if verb=='post' and (path=='/api/repairs' or path.endswith('/attachments')):
            for code in ['429','503']: operation['responses'][code]={'description':'이미지 검증 실행 풀 포화 또는 검사 실행 오류/시간 초과','content':{'application/json':{'schema':ref('Error'),'example':{'code':'PHOTO_VALIDATION_BUSY' if code=='429' else 'PHOTO_VALIDATION_TIMEOUT','status':int(code)}}}}
paths['/api/repairs']['post']['summary']='접수와 원본 파일 저장; 품질 검사 활성화 시 명시적 진단 요청 필요'
paths['/api/repairs']['post']['description']+='\n\n사진 품질 활성화 시 taskId=null이며 자동 진단하지 않습니다. 품질 조회·WARNING 확인 후 /api/repairs/{id}/diagnoses에 attachmentIds를 제출하세요. multipart data는 application/json, files는 원본 파일입니다. targets는 files와 같은 순서입니다.'
paths['/api/auth/login']['post']['description']='실제 계정으로 폼 로그인합니다. username/password를 입력하세요. CSRF는 자동 조회하며 성공한 세션 쿠키로 이후 API를 호출합니다.'
paths['/api/auth/csrf']['get']['description']='로그인 전에도 조회할 수 있습니다. 로그인·로그아웃 후 토큰을 갱신해야 합니다. 이 UI는 매 쓰기 요청 전에 갱신합니다.'
paths['/api/repairs/{id}/events']['get']['description']+='\n\n장시간 연결하는 SSE입니다. Swagger UI의 일반 응답 미리보기보다 EventSource 또는 curl -N을 사용하세요. Last-Event-ID로 재연결합니다.'
doc={'openapi':'3.1.0','info':{'title':'Robot repair backend','version':'2.0.0','description':'모든 업무·내부 API. 요청 DTO는 camelCase, DB 기반 응답은 snake_case, bigint ID는 문자열입니다. 인증 → POST /api/auth/login에서 로그인한 뒤 호출하세요. CSRF는 UI에서 자동 설정합니다. 내부 API는 Authorize의 ServiceToken에 토큰만 입력하세요. 예시 ID와 값은 실제 데이터로 바꿔야 합니다.'},'servers':[{'url':'/'}],'security':[{'SessionCookie':[]}],'paths':paths,'components':{'securitySchemes':{'SessionCookie':{'type':'apiKey','in':'cookie','name':'JSESSIONID'},'ServiceToken':{'type':'http','scheme':'bearer'}},'schemas':S}}
Path('docs/openapi.json').write_text(json.dumps(doc,ensure_ascii=False,indent=2)+'\n')
print(f'Generated OpenAPI: {len(paths)} paths, {sum(map(len,paths.values()))} operations')
