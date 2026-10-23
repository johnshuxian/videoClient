import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import test from 'node:test';

// 在临时仓库里运行完整命令，测试不会接触本机真实签名文件。
function createRecovery(t) {
  const root = mkdtempSync(join(tmpdir(), 'videoclient-signing-test-'));
  const script = join(root, 'scripts', 'recover-android-signing.mjs');
  const directory = join(root, 'android-signing-recovery');
  const encryptedPath = join(root, 'secrets.enc.json');
  mkdirSync(join(root, 'scripts'));
  copyFileSync(new URL('./recover-android-signing.mjs', import.meta.url), script);
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const run = (args, env = {}) => spawnSync(process.execPath, [script, ...args], {
    encoding: 'utf8',
    env: {
      ...process.env,
      ANDROID_RECOVERY_PUBLIC_KEY: '',
      ANDROID_KEYSTORE_BASE64: '',
      ANDROID_KEYSTORE_PASSWORD: '',
      ...env,
    },
  });
  assert.equal(run(['prepare']).status, 0);
  const secrets = {
    ANDROID_KEYSTORE_BASE64: randomBytes(8192).toString('base64'),
    ANDROID_KEYSTORE_PASSWORD: '测试 password $`"\\ with spaces\n',
  };
  const encrypt = () => run(['encrypt', encryptedPath], {
    ...secrets,
    ANDROID_RECOVERY_PUBLIC_KEY: readFileSync(join(directory, 'recovery-public-key-base64.txt'), 'utf8'),
  });
  return { root, directory, encryptedPath, run, secrets, encrypt };
}

test('找回两个原始变量和签名文件，日志和密文不包含明文', (t) => {
  const recovery = createRecovery(t);
  const encrypted = recovery.encrypt();
  assert.equal(encrypted.status, 0, encrypted.stderr);
  const ciphertext = readFileSync(recovery.encryptedPath, 'utf8');
  for (const value of Object.values(recovery.secrets)) {
    assert.ok(!ciphertext.includes(value));
    assert.ok(!(encrypted.stdout + encrypted.stderr).includes(value));
  }
  const decrypted = recovery.run(['decrypt', recovery.encryptedPath]);
  assert.equal(decrypted.status, 0, decrypted.stderr);
  assert.deepEqual(
    JSON.parse(readFileSync(join(recovery.directory, 'android-signing-secrets.json'), 'utf8')),
    recovery.secrets,
  );
  assert.deepEqual(readFileSync(join(recovery.root, 'videoclient-release.jks')),
    Buffer.from(recovery.secrets.ANDROID_KEYSTORE_BASE64, 'base64'));
  assert.equal(readFileSync(join(recovery.root, 'signing-password.txt'), 'utf8'),
    recovery.secrets.ANDROID_KEYSTORE_PASSWORD);
  for (const value of Object.values(recovery.secrets)) {
    assert.ok(!(decrypted.stdout + decrypted.stderr).includes(value));
  }
});

test('密文被篡改时拒绝解密，不写出明文', (t) => {
  const recovery = createRecovery(t);
  assert.equal(recovery.encrypt().status, 0);
  const envelope = JSON.parse(readFileSync(recovery.encryptedPath, 'utf8'));
  const data = Buffer.from(envelope.data, 'base64');
  data[0] ^= 1;
  envelope.data = data.toString('base64');
  writeFileSync(recovery.encryptedPath, JSON.stringify(envelope));
  assert.notEqual(recovery.run(['decrypt', recovery.encryptedPath]).status, 0);
  assert.ok(!existsSync(join(recovery.directory, 'android-signing-secrets.json')));
  assert.ok(!existsSync(join(recovery.root, 'videoclient-release.jks')));
  assert.ok(!existsSync(join(recovery.root, 'signing-password.txt')));
});

test('私钥不匹配时拒绝解密，不写出明文', (t) => {
  const recovery = createRecovery(t);
  const other = createRecovery(t);
  assert.equal(recovery.encrypt().status, 0);
  copyFileSync(join(other.directory, 'recovery-private-key.pem'),
    join(recovery.directory, 'recovery-private-key.pem'));
  assert.notEqual(recovery.run(['decrypt', recovery.encryptedPath]).status, 0);
  assert.ok(!existsSync(join(recovery.directory, 'android-signing-secrets.json')));
  assert.ok(!existsSync(join(recovery.root, 'videoclient-release.jks')));
});

test('拒绝覆盖已有私钥和本机签名文件', (t) => {
  const recovery = createRecovery(t);
  const keyPath = join(recovery.directory, 'recovery-private-key.pem');
  const privateKey = readFileSync(keyPath);
  assert.notEqual(recovery.run(['prepare']).status, 0);
  assert.deepEqual(readFileSync(keyPath), privateKey);
  assert.equal(recovery.encrypt().status, 0);
  const storePath = join(recovery.root, 'videoclient-release.jks');
  writeFileSync(storePath, 'existing signing key');
  assert.notEqual(recovery.run(['decrypt', recovery.encryptedPath]).status, 0);
  assert.equal(readFileSync(storePath, 'utf8'), 'existing signing key');
  assert.ok(!existsSync(join(recovery.directory, 'android-signing-secrets.json')));
});

test('缺少 Secrets 时失败，不生成密文', (t) => {
  const recovery = createRecovery(t);
  assert.notEqual(recovery.run(['encrypt', recovery.encryptedPath]).status, 0);
  assert.ok(!existsSync(recovery.encryptedPath));
});
