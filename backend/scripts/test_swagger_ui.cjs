const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
(async()=>{
 let onload,config;const calls=[];
 const ctx={URL,window:{location:{href:'https://repair.example/backend/swagger-ui/index.html'},addEventListener:(_,f)=>onload=f},document:{getElementById:()=>({})},
 fetch:async(url)=>{calls.push(String(url));return {ok:true,json:async()=>String(url).endsWith('v3/api-docs')?{openapi:'3.1.0'}:{headerName:'X-CSRF-TOKEN',token:'fresh-'+calls.length}}},
 SwaggerUIBundle:options=>{config=options;return {}}};
 vm.runInNewContext(fs.readFileSync(process.argv[2] || require('node:path').join(__dirname,'../src/main/resources/static/swagger-ui/swagger-initializer.js'),'utf8'),ctx);await onload();
 assert.equal(config.spec.servers[0].url,'/backend/');
 const interceptor=config.requestInterceptor;
 let request=await interceptor({url:'https://repair.example/backend/api/auth/login',method:'POST',headers:{}});
 assert.equal(request.headers['X-CSRF-TOKEN'],'fresh-2');
 request=await interceptor({url:'https://repair.example/backend/api/repairs',method:'POST',headers:{'X-CSRF-TOKEN':'stale'}});
 assert.equal(request.headers['X-CSRF-TOKEN'],'fresh-3');
 for(const [url,method] of [['https://repair.example/backend/api/parts','GET'],['https://repair.example/backend/internal/parts','POST'],['https://other.example/api/repairs','POST']]) {
  const before=calls.length;await interceptor({url,method,headers:{}});assert.equal(calls.length,before);
 }
 assert.equal(config.validatorUrl,null);assert.equal(config.persistAuthorization,false);
 console.log('PASS: context path, fresh CSRF per write, GET/internal/cross-origin boundaries, local validation configuration');
})().catch(e=>{console.error(e);process.exit(1)});
