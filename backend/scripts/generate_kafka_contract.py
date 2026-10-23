#!/usr/bin/env python3
import json
from pathlib import Path
U={'type':'string','pattern':'^[1-9][0-9]*$'}
K={'type':'string','format':'uuid'}
T={'type':'string','minLength':1}
I={'type':'integer','minimum':1}
def obj(properties,required=None): return {'type':'object','properties':properties,'required':list(properties) if required is None else required}
def arr(item): return {'type':'array','items':item}
candidate=obj({'rank':I,'confidence':{'type':'number','minimum':0,'maximum':1},'cause':T,'explanation':T})
part=obj({'partId':U,'quantity':I})
evidence=obj({'type':T,'attachmentId':U,'annotation':{'type':'object'},'citation':T},['type'])
attachment=obj({'id':U,'request_id':{'anyOf':[U,{'type':'null'}]},'mime_type':T,'byte_size':I,'sha256':T,'downloadPath':T})
command=obj({'repair':{'type':'object'},'task':{'type':'object'},'attachments':arr(attachment),'attachmentIds':arr(U),'addedAttachmentIds':arr(U),'requestId':{'anyOf':[U,{'type':'null'}]},'requestKey':{'anyOf':[K,{'type':'null'}]},'round':{'type':'integer','minimum':0,'maximum':3},'checkpoint':{'type':['string','null']}})
command['properties'].update({'selectedAttachmentIds':arr(U),'excludedAttachmentIds':arr(U),'explicitlyExcludedAttachmentIds':arr(U)})
payloads={
 'DIAGNOSIS_REQUESTED':command, 'DIAGNOSIS_RESUMED':command,
 'DIAGNOSIS_STARTED':obj({'stage':T}), 'DIAGNOSIS_PROGRESS':obj({'stage':T}),
 'TOOL_EXECUTED':obj({'tool':T,'toolVersion':T}),
 'PHOTO_REQUESTED':obj({'requestKey':K,'round':{'type':'integer','minimum':1,'maximum':3},'bodyPart':T,'description':T,'checkpoint':T}),
 'DIAGNOSIS_COMPLETED':obj({'summary':T,'modelVersion':T,'repairMethod':T,'candidates':dict(arr(candidate),minItems=1),'recommendedParts':arr(part),'evidence':arr(evidence)}),
 'DIAGNOSIS_FAILED':obj({'errorCode':T})}
schema=obj({'eventId':K,'eventType':{'type':'string','enum':list(payloads)},'schemaVersion':{'const':2},'occurredAt':{'type':'string','format':'date-time'},'repairId':U,'taskId':U,'correlationId':K,'inputRevision':I,'executionId':K,'sequence':{'type':'integer','minimum':0},'payload':{'type':'object'}})
schema.update({'$schema':'https://json-schema.org/draft/2020-12/schema','$id':'urn:robot-repair:kafka:v2','title':'Robot repair Kafka envelope v2','allOf':[{'if':{'properties':{'eventType':{'const':event}}},'then':{'properties':{'payload':payload,'sequence':{'const':0} if event in ['DIAGNOSIS_REQUESTED','DIAGNOSIS_RESUMED'] else I}}} for event,payload in payloads.items()]})
Path('docs/kafka-event.schema.json').write_text(json.dumps(schema,ensure_ascii=False,indent=2)+'\n')
base={'eventId':'a0000000-0000-0000-0000-000000000001','eventType':'DIAGNOSIS_REQUESTED','schemaVersion':2,'occurredAt':'2026-10-20T01:00:00Z','repairId':'1','taskId':'1','correlationId':'90000000-0000-0000-0000-000000000001','inputRevision':1,'executionId':'a0000000-0000-0000-0000-000000000003','sequence':0}
a='1';b='2';request='c0000000-0000-0000-0000-000000000001'
photo={'id':a,'request_id':None,'mime_type':'image/png','byte_size':1024,'sha256':'0'*64,'downloadPath':'/internal/attachments/'+a}
source={'repair':{'id':base['repairId'],'product_snapshot':{'id':'1','serial_number':'ROBOT-SEOUL-001','model_id':'1','model_code':'R1','information':{'manufacturer':'Demo'},'components':[]},'symptom':'바퀴 고장 원문','error_codes':['E01','E02'],'firmware_version':'1.2','user_input':{'customerDescription':'기사 원문'}},'task':{'id':base['taskId']},'attachments':[photo],'attachmentIds':[a],'addedAttachmentIds':[],'requestId':None,'requestKey':None,'round':0,'checkpoint':None}
examples=[dict(base,payload=source)]
for n,(kind,payload) in enumerate([
 ('DIAGNOSIS_STARTED',{'stage':'STARTED'}),('DIAGNOSIS_PROGRESS',{'stage':'INSPECTING_PHOTOS'}),('TOOL_EXECUTED',{'tool':'photo-inspector','toolVersion':'1','modelVersion':'team-model-version','output':{'note':'example'}}),('PHOTO_REQUESTED',{'requestKey':request,'round':1,'bodyPart':'wheel','description':'바퀴 하부 사진','checkpoint':'langgraph-thread:checkpoint'})],start=1):
 examples.append(dict(base,eventId=f'a0000000-0000-0000-0000-{n+10:012d}',eventType=kind,sequence=n,payload=payload))
resume=dict(source,requestId="1",requestKey=request,round=1,checkpoint='langgraph-thread:checkpoint',attachmentIds=[a,b],addedAttachmentIds=[b],attachments=[photo,dict(photo,id=b,request_id="1",downloadPath='/internal/attachments/'+b)])
execution='a0000000-0000-0000-0000-000000000099'
examples.append(dict(base,eventId='a0000000-0000-0000-0000-000000000021',eventType='DIAGNOSIS_RESUMED',executionId=execution,inputRevision=2,payload=resume))
examples.append(dict(base,eventId='a0000000-0000-0000-0000-000000000022',eventType='DIAGNOSIS_COMPLETED',executionId=execution,inputRevision=2,sequence=1,payload={'summary':'모터 고장 추정','modelVersion':'team-model-version','repairMethod':'모터 교체','candidates':[{'rank':1,'confidence':0.85,'cause':'motor obstruction','explanation':'사진 관찰'}],'recommendedParts':[{'partId':'1','quantity':1}],'evidence':[{'type':'PHOTO','attachmentId':b,'annotation':{'x':0.1,'y':0.2,'width':0.3,'height':0.3}},{'type':'MANUAL','citation':'DEMO-MANUAL §2'}]}))
examples.append(dict(base,eventId='a0000000-0000-0000-0000-000000000023',eventType='DIAGNOSIS_FAILED',inputRevision=4,sequence=1,payload={'errorCode':'INCONCLUSIVE_AFTER_THIRD_PHOTO'}))
Path('docs/kafka-examples.json').write_text(json.dumps(examples,ensure_ascii=False,indent=2)+'\n')
