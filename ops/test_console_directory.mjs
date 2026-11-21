import assert from 'node:assert/strict'
import {randomBytes} from 'node:crypto'
import {readFileSync} from 'node:fs'
import {setTimeout as sleep} from 'node:timers/promises'

const admin = 'https://admin.naturalstreaming.net'
const api = 'https://api.naturalstreaming.net'
const owner = JSON.parse(readFileSync('.local/console-access.json'))
const suffix = randomBytes(5).toString('hex')
const scopedUser = 'console-test-' + suffix
const memberUser = 'member-test-' + suffix
const password = () => randomBytes(15).toString('base64url')
const initial = password()
const changed = password()
let initialMemberToken

async function request(base, path, {method = 'GET', input, cookie, token, status = 200, origin = true} = {}) {
    const headers = {}
    if (origin && base === admin) headers.Origin = admin
    if (cookie) headers.Cookie = cookie
    if (token) headers.Authorization = 'Bearer ' + token
    if (input !== undefined) headers['Content-Type'] = 'application/json'
    const send = () => fetch(base + path, {method,headers,body: input === undefined ? undefined : JSON.stringify(input)})
    let response = await send()
    for (let retry = 0; response.status === 429 && retry < 5; retry++) {
        await response.body?.cancel()
        await sleep(6500)
        response = await send()
    }
    assert.equal(response.status, status, method + ' ' + path + ' returned ' + response.status +
        (response.status === status ? '' : ': ' + (await response.text()).slice(0,400)))
    return response
}

async function consoleLogin(username, pass, status = 204) {
    const res = await request(admin,'/admin/session',{method:'POST',input:{username,password:pass},status})
    return res.headers.get('set-cookie')?.split(';')[0]
}
async function memberLogin(pass, status = 200) {
    const res = await request(api,'/v1/auth/login',{method:'POST',input:{username:memberUser,password:pass},status})
    return (await res.json()).access_token
}
const ownerCookie = await consoleLogin(owner.username,owner.password)
const ownerRequest = (path, method = 'GET', input, status = 200) => request(admin,'/admin'+path,{method,input,cookie:ownerCookie,status})
const ownerProfile = await (await ownerRequest('/me')).json()
assert.ok(ownerProfile.permissions.includes('roles.manage'))
const roles = await (await ownerRequest('/roles')).json()
assert.ok(roles.filter(role => role.builtin).length >= 3)

const scopedRole = await (await ownerRequest('/roles','POST',{name:'测试角色 '+suffix,permissions:['assets.read']})).json()
const scoped = await (await ownerRequest('/accounts','POST',{username:scopedUser,password:initial,role_id:scopedRole.id})).json()
let member
try {
    let scopedCookie = await consoleLogin(scopedUser,initial)
    const call = (path, method = 'GET', input, status = 200, origin = true) =>
        request(admin,'/admin'+path,{method,input,cookie:scopedCookie,status,origin})
    assert.deepEqual((await (await call('/me')).json()).permissions,['assets.read'])
    await call('/assets')
    await call('/presets','GET',undefined,403)
    await call('/members','GET',undefined,403)
    await call('/presets','POST',{},403)
    await call('/password','POST',{current_password:initial,new_password:changed},403,false)
    await call('/password','POST',{current_password:password(),new_password:changed},400)
    const another = await consoleLogin(scopedUser,initial)
    await call('/password','POST',{current_password:initial,new_password:changed},204)
    await call('/me','GET',undefined,401)
    await request(admin,'/admin/me',{cookie:another,status:401})
    await consoleLogin(scopedUser,initial,401)
    scopedCookie = await consoleLogin(scopedUser,changed)
    await ownerRequest('/roles/'+scopedRole.id,'PUT',{name:scopedRole.name,permissions:['assets.read','presets.read','accounts.manage']})
    await call('/presets')
    const escalated = await call('/accounts','POST',{username:'forbidden-'+suffix,password:password(),role_id:1},403)
    assert.ok((await escalated.json()).error)
    await ownerRequest('/accounts/'+scoped.id,'PUT',{enabled:false,role_id:scopedRole.id})
    assert.equal((await (await call('/me','GET',undefined,401)).json()).error,'session_revoked')

    const initialMemberPassword = password()
    const nextMemberPassword = password()
    member = await (await ownerRequest('/members','POST',{username:memberUser,password:initialMemberPassword})).json()
    initialMemberToken = await memberLogin(initialMemberPassword)
    const catalog = await (await request(api,'/v1/presets',{token:initialMemberToken})).json()
    assert.equal(catalog.find(row => row.preset_id === 'coastal-home-light-rain-study').revision,4)
    await ownerRequest('/members/'+member.id+'/password','POST',{password:nextMemberPassword},204)
    assert.equal((await (await request(api,'/v1/me',{token:initialMemberToken,status:401})).json()).error,'session_revoked')
    await memberLogin(initialMemberPassword,401)
    const nextMemberToken = await memberLogin(nextMemberPassword)
    await ownerRequest('/members/'+member.id,'PUT',{enabled:false})
    assert.equal((await (await request(api,'/v1/me',{token:nextMemberToken,status:401})).json()).error,'session_revoked')
    await memberLogin(nextMemberPassword,401)
    await ownerRequest('/members/'+member.id,'PUT',{enabled:true})
    const enabledToken = await memberLogin(nextMemberPassword)
    await request(api,'/v1/auth/logout',{method:'POST',token:enabledToken,status:204})
    console.log('Role delegation, denied escalation, self-password rotation, and App member lifecycle verified')
} finally {
    if (member) await ownerRequest('/members/'+member.id,'PUT',{enabled:false})
    await ownerRequest('/accounts/'+scoped.id,'PUT',{enabled:false,role_id:scopedRole.id})
    await ownerRequest('/session','DELETE',undefined,204)
}
