import assert from 'node:assert/strict'
import {createHash} from 'node:crypto'
import {readFileSync, writeFileSync} from 'node:fs'

const admin = 'https://admin.naturalstreaming.net'
const api = 'https://api.naturalstreaming.net'
const media = 'https://media.naturalstreaming.net'
const privateAdmin = JSON.parse(readFileSync('.local/console-access.json'))
const account = JSON.parse(readFileSync('.local/phase1-app-account.json'))
const docs = JSON.parse(readFileSync('.local/phase1-sample-presets.json', 'utf8').replace(/^\uFEFF/, ''))
docs.sort((a,b) => a.revision - b.revision)

async function request(url, options, status = 200) {
    const response = await fetch(url, options)
    if (response.status !== status) {
        const error = (await response.text()).slice(0, 350)
        throw new Error(url + ' returned ' + response.status + ', expected ' + status + ': ' + error)
    }
    return response
}

function json(body) {
    return {headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)}
}
const auth = token => ({Authorization:'Bearer ' + token})

const launch = json({username:privateAdmin.username, password:privateAdmin.password})
const signIn = await request(admin + '/admin/session', {
    method:'POST', headers:{...launch.headers, Origin:admin}, body:launch.body,
}, 204)
const cookie = signIn.headers.get('set-cookie').split(';')[0]
async function adminCall(path, method = 'GET', body) {
    const data = body === undefined ? {} : json(body)
    return request(admin + '/admin' + path, {method, body:data.body, headers:{...data.headers, Origin:admin, Cookie:cookie}})
}

const rejectedUpload = await request(admin + '/admin/assets/import', {
    method:'POST', headers:{Origin:admin, 'Content-Type':'multipart/form-data'}, body:'malformed request',
}, 401)
assert.equal((await rejectedUpload.json()).error,'unauthorized')

const form = new FormData()
form.append('file', new Blob([readFileSync('audioBank.zip')]), 'audioBank.zip')
const upload = await request(admin + '/admin/assets/import', {
    method:'POST', headers:{Origin:admin, Cookie:cookie}, body:form, signal:AbortSignal.timeout(290000),
})
const counts = await upload.json()
assert.equal(counts.added + counts.reused, 60)
assert.equal(counts.skipped_presets, 2)
const assets = await (await adminCall('/assets')).json()
assert.equal(assets.length, 60)
const byHash = new Map(assets.map(asset => [asset.sha256, asset]))
const catalogZip = await adminCall('/assets/export')
writeFileSync('.local/phase1-export.zip', Buffer.from(await catalogZip.arrayBuffer()))
console.log('asset import and export', counts)

const themes = await (await adminCall('/themes')).json()
for (const [id,name] of [['coastal-home','海岸的家'],['forest-in-the-morning','森林的早晨'],['tender-piano','柔软的钢琴']]) {
    if (!themes.some(theme => theme.id === id)) await adminCall('/themes', 'POST', {id,name})
}
const adjustments = []
for (const preset of docs) {
    for (const track of preset.tracks) {
        for (const clip of track.clips) {
            const asset = byHash.get(clip.sha256)
            assert.equal(asset.asset_id, clip.asset_id)
            if (clip.end_frame_exclusive > asset.frames) {
                assert.equal(clip.sha256, '80e7b05f2eb3f0a562c4c29f5b34a031b1e0c3d45342fa274e011cdbe98ce857')
                assert.ok(clip.end_frame_exclusive - asset.frames <= 616)
                adjustments.push([preset.revision,clip.end_frame_exclusive,asset.frames])
                clip.end_frame_exclusive = asset.frames
            }
        }
    }
    await adminCall('/presets', 'POST', preset)
}
console.log('preset boundary adjustments', adjustments)

async function login() {
    return (await (await request(api + '/v1/auth/login', {method:'POST', ...json({
        username:account.username,password:account.password,
    })})).json()).access_token
}
const first = await login()
const before = await (await request(api + '/v1/presets', {headers:auth(first)})).json()
const id = docs[0].preset_id
assert.equal(id, docs[1].preset_id)
const presetUrl = api + '/v1/presets/' + id + '/versions/'
if (!before.some(row => row.preset_id === id)) {
    await request(presetUrl + '4', {headers:auth(first)}, 404)
    await adminCall('/presets/' + id + '/3/publish','POST')
    const atR3 = await (await request(api + '/v1/presets', {headers:auth(first)})).json()
    assert.equal(atR3.find(row => row.preset_id === id).revision, 3)
}

async function version(revision, token = first) {
    return (await request(presetUrl + revision, {headers:auth(token)})).json()
}
const previous = await version(3)
assert.equal(previous.assets.length, 5)
await adminCall('/presets/' + id + '/4/publish','POST')
const updated = await version(4)
assert.deepEqual(previous.assets, updated.assets)
assert.notEqual(previous.preset.tracks[0].gain, updated.preset.tracks[0].gain)
const catalog = await (await request(api + '/v1/presets', {headers:auth(first)})).json()
assert.equal(catalog.find(row => row.preset_id === id).revision, 4)
console.log('published r3 and r4, catalog is r4, same five dependencies')

for (const asset of updated.assets) {
    const url = media + '/v1/media/' + asset.sha256
    const bytes = Buffer.from(await (await request(url, {headers:auth(first)})).arrayBuffer())
    assert.equal(bytes.length, asset.byte_size)
    assert.equal(createHash('sha256').update(bytes).digest('hex'), asset.sha256)
}
const sample = updated.assets[0]
const audioUrl = media + '/v1/media/' + sample.sha256
const partial = await request(audioUrl, {headers:{...auth(first), Range:'bytes=0-15'}}, 206)
assert.equal((await partial.arrayBuffer()).byteLength,16)
assert.equal(partial.headers.get('content-range'), 'bytes 0-15/' + sample.byte_size)
await request(audioUrl, {}, 401)
const second = await login()
for (const endpoint of [api + '/v1/me',audioUrl]) {
    const revoked = await request(endpoint, {headers:auth(first)}, 401)
    assert.equal((await revoked.json()).error,'session_revoked')
}
await request(api + '/v1/me', {headers:auth(second)})
await request(api + '/v1/auth/logout', {method:'POST', headers:auth(second)}, 204)
await request(admin + '/admin/session', {method:'DELETE',headers:{Origin:admin,Cookie:cookie}}, 204)
console.log('authenticated download hashes, byte range, device revocation and logout verified')
