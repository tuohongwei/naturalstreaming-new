import { spawnSync } from 'node:child_process'
import { createHash, randomBytes } from 'node:crypto'
import { existsSync, readFileSync, writeFileSync } from 'node:fs'

const [key] = process.argv.slice(2)
if (!key) throw new Error('Usage: node ops/deploy_phase1.mjs <SSH key path>')
const accountPath = '.local/phase1-app-account.json'
const api = 'https://api.naturalstreaming.net'
if (!existsSync(accountPath)) {
    const account = {api, username:'naturalstreaming-phase1', password:randomBytes(24).toString('base64url')}
    writeFileSync(accountPath, JSON.stringify(account, null, 2) + '\n', {flag:'wx', mode:0o600})
}
const account = JSON.parse(readFileSync(accountPath))
if (!/^[a-z0-9-]+$/.test(account.username) || !/^[A-Za-z0-9_-]{32}$/.test(account.password)) {
    throw new Error('Invalid bootstrap account format')
}

function ssh(command, input, capture = false) {
    const result = spawnSync('ssh', ['-i', key, '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes',
        '-o', 'ConnectTimeout=10', 'ec2-user@13.214.63.156', command],
        {input, maxBuffer: 1024 * 1024, stdio: ['pipe', capture ? 'pipe' : 'inherit', 'inherit']})
    if (result.status !== 0) throw new Error('SSH operation failed: ' + (result.error?.message ?? result.status))
    return result.stdout?.toString().trim()
}

const release = 'phase1-' + Date.now()
const dir = '/srv/naturalstreaming/releases/' + release
const jar = readFileSync('backend/target/naturalstreaming-2.0.0.jar')
const checksum = createHash('sha256').update(jar).digest('hex')
const bootstrap = Buffer.from('NS_TEST_USER=' + account.username + '\nNS_TEST_PASSWORD=' + account.password + '\n')
ssh("sudo -n bash -ec 'umask 077; set -o noclobber; if [ -e /etc/naturalstreaming/phase1-test.env ]; then cmp -s /etc/naturalstreaming/phase1-test.env -; else cat > /etc/naturalstreaming/phase1-test.env; fi'", bootstrap)
ssh('sudo -n mkdir -p ' + dir)
ssh('sudo -n tee ' + dir + '/naturalstreaming-admin.jar >/dev/null', jar)
const deployed = ssh('sudo -n sha256sum ' + dir + '/naturalstreaming-admin.jar', undefined, true)
if (deployed.split(' ')[0] !== checksum) throw new Error('Upload checksum mismatch')
ssh('sudo -n ln ' + dir + '/naturalstreaming-admin.jar ' + dir + '/naturalstreaming-api.jar')
ssh('sudo -n flock -n /srv/naturalstreaming/deploy.lock python3 - ' + release, readFileSync('ops/deploy_phase1.py'))
console.log('Activated ' + release)
