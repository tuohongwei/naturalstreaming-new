// DOM interaction regression against deployed HTML/JS and real console APIs.
// Requires jsdom 26.1.0 installed under ignored .local/console-tests; not a visual browser test.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {JSDOM}=require('../.local/console-tests/node_modules/jsdom');
const config=JSON.parse(fs.readFileSync(path.resolve('.local/console-access.json'),'utf8'));
const base=config.url,ua='NaturalStreaming/1.0 (Console DOM test)';let cookie='',win,fixtureUsername;
async function remote(url,options={}){const h=new Headers(options.headers||{});h.set('User-Agent',ua);h.set('Origin',base);if(cookie)h.set('Cookie',cookie);const r=await fetch(new URL(url,base),{...options,headers:h});const sc=r.headers.get('set-cookie');if(sc)cookie=sc.split(';')[0];return r;}
async function wait(fn,label,limit=20000){const start=Date.now();while(Date.now()-start<limit){if(fn())return;await new Promise(r=>setTimeout(r,40));}throw new Error('Timed out: '+label);}
const tick=()=>new Promise(r=>setTimeout(r,120));
function click(selector){const e=win.document.querySelector(selector);assert(e,'Missing control: '+selector);e.click();}
function value(selector,text){const e=win.document.querySelector(selector);assert(e,'Missing input: '+selector);e.value=text;e.dispatchEvent(new win.Event('input',{bubbles:true}));}
function submit(selector){win.document.querySelector(selector).dispatchEvent(new win.Event('submit',{bubbles:true,cancelable:true}));}
async function call(p,method='GET',body){const session=await (await remote('/console/session')).json();const h={'X-CSRF-Token':session.csrfToken};if(body!==undefined)h['Content-Type']='application/json';const r=await remote('/console/api/'+p,{method,headers:h,body:body===undefined?undefined:JSON.stringify(body)});const d=await r.json();assert(r.ok,'API status '+r.status+' on '+p);return d;}
async function main(){
 const html=await (await remote('/')).text();const dom=new JSDOM(html,{url:base,runScripts:'outside-only',pretendToBeVisual:true});win=dom.window;
 win.Headers=Headers;win.fetch=remote;win.confirm=()=>true;win.structuredClone=structuredClone;win.Blob=Blob;win.File=File;win.AbortController=AbortController;win.AbortSignal=AbortSignal;
 Object.defineProperty(win,'crypto',{value:require('node:crypto').webcrypto});
 win.HTMLDialogElement.prototype.showModal=function(){this.open=true;};win.HTMLDialogElement.prototype.close=function(){this.open=false;};
 win.HTMLMediaElement.prototype.pause=function(){};
 if(html.includes('/console-policy.js'))win.eval(await (await remote('/console-policy.js')).text());
 const code=await (await remote('/console.js')).text();win.eval(code);
 await wait(()=>!win.document.querySelector('#login').hidden,'login form');value('#login-form [name=username]',config.username);value('#login-form [name=password]',config.password);submit('#login-form');
 await wait(()=>win.document.querySelector('#main h1')?.textContent==='内容概览','dashboard');await tick();
 assert(win.document.querySelector('#login-form [name=password]').value==='','Login must clear password from hidden form');
 const demo=await call('admin/themes/integration-tone-demo/versions/1');const id='console-test-ui-'+Date.now();demo.scene.id=id;demo.scene.name='界面回归合成测试';demo.manifest.theme_id=id;
 const draft={scene:demo.scene,manifest:demo.manifest,license:demo.license};await call(`admin/themes/${id}/versions/1`,'PUT',draft);
 click('nav [data-page=themes]');await wait(()=>win.document.querySelector('#show-fixtures'),'theme list');const f=win.document.querySelector('#show-fixtures');f.checked=true;f.dispatchEvent(new win.Event('change',{bubbles:true}));
 await wait(()=>win.document.querySelector(`[data-action=edit][data-id="${id}"]`),'fixture theme');await tick();click(`[data-action=edit][data-id="${id}"]`);
 await wait(()=>win.document.querySelector('[data-scene-field=name]'),'theme editor');await tick();value('[data-scene-field=name]','界面回归已编辑');click('[data-action=save]');
 await wait(()=>win.document.querySelector('.save-bar')?.textContent.includes('修订 2'),'draft save');await tick();click('[data-action=validate]');
 await wait(()=>win.document.querySelector('#main .info-box')?.textContent.includes('校验通过'),'validation');await tick();click('[data-action=publish]');
 await wait(()=>!win.document.querySelector('[data-scene-field=name]')?.disabled===false&&win.document.querySelector('.editor-top .badge')?.textContent.includes('已发布'),'publish');await tick();
 click('[data-tab=presets]');await wait(()=>win.document.querySelectorAll('.preset').length===5,'preset tab');click('[data-tab=license]');await wait(()=>win.document.querySelector('[data-license=source]'),'license tab');click('[data-tab=json]');await wait(()=>win.document.querySelector('[data-json=scene]'),'JSON tab');
 click('[data-action=unpublish]');await wait(()=>!win.document.querySelector('[data-action=unpublish]'),'unpublish');await tick();
 // Incomplete uploader-owned draft must not inject HTML and must have a JSON repair route.
 const badId='console-test-incomplete-'+Date.now();const bad={scene:{id:badId,schema_version:1,name:'不完整草稿',tracks:{length:'<button id="injected-control" data-action="logout">伪造</button>'}},manifest:{theme_id:badId,schema_version:1,files:[]},license:{reviewed:false}};
 await call(`admin/themes/${badId}/versions/1`,'PUT',bad);click('nav [data-page=themes]');await wait(()=>win.document.querySelector('#show-fixtures'),'return themes');
 let toggle=win.document.querySelector('#show-fixtures');toggle.checked=false;toggle.dispatchEvent(new win.Event('change',{bubbles:true}));await tick();await tick();
 toggle=win.document.querySelector('#show-fixtures');toggle.checked=true;toggle.dispatchEvent(new win.Event('change',{bubbles:true}));
 await wait(()=>win.document.querySelector(`[data-action=edit][data-id="${badId}"]`),'incomplete card');await tick();assert(!win.document.querySelector('#injected-control'),'Draft metadata must not inject controls');
 click(`[data-action=edit][data-id="${badId}"]`);await wait(()=>win.document.querySelector('[data-json=scene]'),'incomplete JSON repair');
 const repairedScene=structuredClone(demo.scene),repairedManifest=structuredClone(demo.manifest);repairedScene.id=badId;repairedManifest.theme_id=badId;value('[data-json=scene]',JSON.stringify(repairedScene));value('[data-json=manifest]',JSON.stringify(repairedManifest));click('[data-action=save]');await wait(()=>win.document.querySelector('[data-scene-field=name]'),'repaired structured editor');await tick();
 // Directory import, real binary upload, and review invalidation on changed media.
 const uploadDoc=await call('admin/themes/integration-tone-demo/versions/1'),uploadId='console-test-upload-'+Date.now();uploadDoc.scene.id=uploadId;uploadDoc.manifest.theme_id=uploadId;
 const payloads=new Map();for(const item of uploadDoc.manifest.files)if(!payloads.has(item.sha256))payloads.set(item.sha256,Buffer.from(await (await remote('/console/api/uploads/'+item.sha256+'/content')).arrayBuffer()));
 const makeFiles=()=>{
  const list=[];function add(name,bytes){const file=new File([bytes],name.split('/').at(-1));Object.defineProperty(file,'webkitRelativePath',{value:uploadId+'/'+name});list.push(file);}
  add('scene.json',JSON.stringify(uploadDoc.scene));add('manifest.json',JSON.stringify(uploadDoc.manifest));for(const item of uploadDoc.manifest.files)add(item.path,payloads.get(item.sha256));return list;
 };
 async function importFiles(){click('nav [data-page=upload]');await wait(()=>win.document.querySelector('#upload-folder'),'upload pane');await tick();Object.defineProperty(win.document.querySelector('#upload-folder'),'files',{value:makeFiles(),configurable:true});click('[data-action=upload-start]');await wait(()=>win.document.querySelector('#upload-status')?.textContent.includes('上传完成')&&!win.document.querySelector('[data-action=upload-start]').disabled,'directory upload',60000);await tick();}
 const first=uploadDoc.manifest.files[0],modified=Buffer.from(payloads.get(first.sha256));modified[100]^=1;first.sha256=require('node:crypto').createHash('sha256').update(modified).digest('hex');payloads.set(first.sha256,modified);
 await importFiles();let uploaded=await call(`admin/themes/${uploadId}/versions/1`);assert(uploaded.license.reviewed===false,'New imports must not claim content review');
 uploaded.license={source:'Generated synthetic DOM regression fixture',license:'CC0',reviewed:true};await call(`admin/themes/${uploadId}/versions/1`,'PUT',{scene:uploaded.scene,manifest:uploaded.manifest,license:uploaded.license,revision:uploaded.revision});
 const modifiedAgain=Buffer.from(modified);modifiedAgain[102]^=1;first.sha256=require('node:crypto').createHash('sha256').update(modifiedAgain).digest('hex');payloads.set(first.sha256,modifiedAgain);await importFiles();uploaded=await call(`admin/themes/${uploadId}/versions/1`);assert(uploaded.license.reviewed===false,'Changed media must clear previously reviewed license');
 click('nav [data-page=accounts]');await wait(()=>win.document.querySelector('[data-action=create-account]'),'accounts');await tick();click('[data-action=create-account]');
 await wait(()=>win.document.querySelector('#account-form'),'account dialog');const username='console-test-ui-'+Date.now();fixtureUsername=username;value('#account-form [name=username]',username);value('#account-form [name=password]',require('node:crypto').randomBytes(16).toString('hex'));submit('#account-form');
 await wait(()=>[...win.document.querySelectorAll('tbody tr')].some(r=>r.textContent.includes(username)),'created account');await tick();
 let row=[...win.document.querySelectorAll('tbody tr')].find(r=>r.textContent.includes(username));row.querySelector('[data-action=grants]').click();await wait(()=>win.document.querySelector('#dialog').open&&win.document.querySelector('#grant-form'),'grant dialog');
 const cb=win.document.querySelector('#grant-form input[value="integration-tone-demo"]');cb.checked=true;submit('#grant-form');await wait(()=>!win.document.querySelector('#dialog').open,'grant save');await tick();
 const accountId=row.querySelector('[data-action=grants]').dataset.id;const grants=await call(`admin/accounts/${accountId}/themes`);assert(grants.entitlements.some(e=>e.theme_id==='integration-tone-demo'&&e.allowed),'Grant must persist');
 row=[...win.document.querySelectorAll('tbody tr')].find(r=>r.textContent.includes(username));row.querySelector('[data-action=grants]').click();await wait(()=>win.document.querySelector('#dialog').open&&win.document.querySelector('#grant-form'),'revoke dialog');win.document.querySelector('#grant-form input[value="integration-tone-demo"]').checked=false;win.confirm=()=>false;submit('#grant-form');await tick();assert((await call(`admin/accounts/${accountId}/themes`)).entitlements.some(e=>e.theme_id==='integration-tone-demo'&&e.allowed),'Cancelled revoke must preserve permission');win.confirm=()=>true;submit('#grant-form');await wait(()=>!win.document.querySelector('#dialog').open,'confirmed revoke');await tick();assert((await call(`admin/accounts/${accountId}/themes`)).entitlements.some(e=>e.theme_id==='integration-tone-demo'&&!e.allowed),'Confirmed revoke must persist');
 row=[...win.document.querySelectorAll('tbody tr')].find(r=>r.textContent.includes(username));row.querySelector('[data-action=toggle-account]').click();await wait(()=>[...win.document.querySelectorAll('tbody tr')].find(r=>r.textContent.includes(username))?.textContent.includes('已禁用'),'account disable');await tick();
 click('nav [data-page=audit]');await wait(()=>win.document.querySelector('#main h1')?.textContent==='操作记录','audit page');await tick();click('[data-action=password]');await wait(()=>win.document.querySelector('#password-form'),'password dialog');click('[data-action=close-dialog]');await tick();
 click('[data-action=logout]');await wait(()=>!win.document.querySelector('#login').hidden,'logout');assert(win.document.querySelector('#login-form [name=password]').value==='','Logout must not retain password');
}
main().then(()=>{console.log('PASS real API DOM interactions');win?.close();}).catch(async e=>{if(fixtureUsername){try{const rows=await call('admin/accounts');const a=rows.accounts.find(x=>x.username===fixtureUsername);if(a)await call('admin/accounts/'+a.id,'PUT',{enabled:false});}catch{}}console.error(e.message);console.error('Upload status:',win?.document.querySelector('#upload-status')?.textContent?.slice(0,700));console.error('UI messages:',[...(win?.document.querySelectorAll('.toast')||[])].map(x=>x.textContent).join(' | '));win?.close();process.exitCode=1;});
