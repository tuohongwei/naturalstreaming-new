import assert from 'node:assert/strict'
import {spawn} from 'node:child_process'
import {existsSync,mkdtempSync,readFileSync} from 'node:fs'
import {resolve} from 'node:path'
import {setTimeout as sleep} from 'node:timers/promises'

const [edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'] = process.argv.slice(2)
const profile = mkdtempSync(resolve('.local/phase1-browser-'))
const browser = spawn(edge, [
    '--headless=new','--window-size=1400,950','--no-first-run','--disable-gpu','--mute-audio',
    '--remote-debugging-address=127.0.0.1','--remote-debugging-port=0','--user-data-dir=' + profile,
    'https://admin.naturalstreaming.net/',
], {windowsHide:true,stdio:'ignore'})
const admin = JSON.parse(readFileSync('.local/console-access.json'))
let socket

async function until(check, description) {
    for (let attempt = 0; attempt < 120; attempt++) {
        const result = await check()
        if (result) return result
        await sleep(150)
    }
    throw new Error('Timed out: ' + description)
}

try {
    const portFile = resolve(profile,'DevToolsActivePort')
    await until(() => existsSync(portFile), 'Edge debug port')
    const port = readFileSync(portFile,'utf8').split(/\r?\n/)[0]
    const target = await until(async () =>
        (await (await fetch('http://127.0.0.1:' + port + '/json/list')).json()).find(page => page.type === 'page'),
    'Edge page')
    socket = new WebSocket(target.webSocketDebuggerUrl)
    await new Promise((ok,fail) => {
        socket.addEventListener('open',ok,{once:true})
        socket.addEventListener('error',fail,{once:true})
    })

    let requestId = 0
    const callbacks = new Map()
    socket.addEventListener('message', event => {
        const reply = JSON.parse(event.data)
        callbacks.get(reply.id)?.(reply)
        callbacks.delete(reply.id)
    })
    async function evaluate(expression) {
        const id = ++requestId
        const reply = await new Promise(ok => {
            callbacks.set(id,ok)
            socket.send(JSON.stringify({
                id, method:'Runtime.evaluate', params:{expression,awaitPromise:true,returnByValue:true,userGesture:true},
            }))
        })
        if (reply.error) throw new Error(reply.error.message)
        if (reply.result.exceptionDetails) throw new Error(reply.result.exceptionDetails.exception?.description ?? reply.result.exceptionDetails.text)
        return reply.result.result.value
    }

    await until(() => evaluate('!!document.querySelector("input[type=password]")'), 'admin login form')
    await evaluate(`(() => {
        const password = document.querySelector("input[type=password]");
        const form = password.form;
        form.querySelector("input").value = ${JSON.stringify(admin.username)};
        password.value = ${JSON.stringify(admin.password)};
        form.requestSubmit();
    })()`)
    await until(() => evaluate('document.querySelector("#preset-list").innerText.includes("coastal-home-light-rain-study")'), 'current preset list')
    await evaluate(`(() => {
        const row = [...document.querySelectorAll("#preset-list tr")].find(x =>
            x.innerText.includes("coastal-home-light-rain-study") && x.innerText.includes("当前发布"));
        const preview = [...row.querySelectorAll("button")].find(x => x.textContent.trim() === "试听预设");
        preview.click();
    })()`)
    const previewLabel = await until(async () => {
        const state = await evaluate(`({
            label: document.querySelector("#preview-label").textContent,
            error: document.querySelector(".error:not([hidden])")?.textContent,
        })`)
        if (state.error) throw new Error(state.error)
        return state.label.startsWith("正在试听") && state.label
    }, 'browser decoding and preview mix')
    assert.match(previewLabel, /coastal-home-light-rain-study · r4/)
    await sleep(800)
    await evaluate('document.querySelector("#stop-preview").click();document.querySelector("#logout").click()')
    console.log('Edge preview started and stopped: ' + previewLabel)
} finally {
    socket?.close()
    browser.kill()
}
