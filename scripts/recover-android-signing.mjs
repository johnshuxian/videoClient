import {
  constants,
  createCipheriv,
  createDecipheriv,
  generateKeyPairSync,
  privateDecrypt,
  publicEncrypt,
  randomBytes,
} from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const recoveryDirectory = join(root, 'android-signing-recovery');
const privateKeyPath = join(recoveryDirectory, 'recovery-private-key.pem');
const publicKeyPath = join(recoveryDirectory, 'recovery-public-key-base64.txt');
const command = process.argv[2];

try {
  if (command === 'prepare') {
    // 不覆盖旧密钥，避免已下载的密文因为私钥被替换而无法解密。
    if (existsSync(privateKeyPath) || existsSync(publicKeyPath)) {
      console.error('找回密钥已存在，请继续使用现有公钥和私钥。');
      process.exit(1);
    }
    const { publicKey, privateKey } = generateKeyPairSync('rsa', {
      modulusLength: 3072,
      publicKeyEncoding: { type: 'spki', format: 'pem' },
      privateKeyEncoding: { type: 'pkcs8', format: 'pem' },
    });
    mkdirSync(recoveryDirectory, { recursive: true, mode: 0o700 });
    writeFileSync(privateKeyPath, privateKey, { flag: 'wx', mode: 0o600 });
    writeFileSync(publicKeyPath, Buffer.from(publicKey).toString('base64'), { flag: 'wx' });
    console.log(`公钥已保存到 ${publicKeyPath}；配套私钥请仅保存在本机。`);
  } else if (command === 'encrypt') {
    const { ANDROID_RECOVERY_PUBLIC_KEY, ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD } = process.env;
    if (!ANDROID_RECOVERY_PUBLIC_KEY || !ANDROID_KEYSTORE_BASE64 || !ANDROID_KEYSTORE_PASSWORD || !process.argv[3]) {
      console.error('缺少找回公钥、Android 签名 Secrets 或密文输出路径。');
      process.exit(1);
    }

    // JKS 通常超过 RSA 的单次加密长度，因此用 AES-GCM 加密内容、RSA-OAEP 保护 AES 密钥。
    const key = randomBytes(32);
    const iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', key, iv);
    const data = Buffer.concat([
      cipher.update(JSON.stringify({ ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD }), 'utf8'),
      cipher.final(),
    ]);
    const encryptedKey = publicEncrypt({
      key: Buffer.from(ANDROID_RECOVERY_PUBLIC_KEY, 'base64'),
      padding: constants.RSA_PKCS1_OAEP_PADDING,
      oaepHash: 'sha256',
    }, key);
    const envelope = {
      encryptedKey: encryptedKey.toString('base64'),
      iv: iv.toString('base64'),
      tag: cipher.getAuthTag().toString('base64'),
      data: data.toString('base64'),
    };
    if (process.argv[3] === '-') {
      // 分行输出加密包，方便从 Actions 日志复制，也避免单行过长被截断。
      const encoded = Buffer.from(JSON.stringify(envelope)).toString('base64');
      console.log('-----BEGIN ANDROID SIGNING RECOVERY-----');
      console.log(encoded.match(/.{1,120}/g).join('\n'));
      console.log('-----END ANDROID SIGNING RECOVERY-----');
    } else {
      writeFileSync(resolve(process.argv[3]), JSON.stringify(envelope), { flag: 'wx', mode: 0o600 });
      console.log('已生成加密的签名材料，请将密文文件保存到本机解密。');
    }
  } else if (command === 'decrypt' && process.argv[3]) {
    const secretsPath = join(recoveryDirectory, 'android-signing-secrets.json');
    const storePath = join(root, 'videoclient-release.jks');
    const passwordPath = join(root, 'signing-password.txt');
    if ([secretsPath, storePath, passwordPath].some(existsSync)) {
      console.error('签名文件或解密结果已存在，请先备份并移走，防止覆盖。');
      process.exit(1);
    }
    const input = readFileSync(resolve(process.argv[3]), 'utf8').trim();
    // 同时支持日志里的密文块和此前下载的加密 JSON 文件，沿用原来的本机私钥。
    const block = input.match(/-----BEGIN ANDROID SIGNING RECOVERY-----([\s\S]*?)-----END ANDROID SIGNING RECOVERY-----/);
    const envelope = JSON.parse(block ? Buffer.from(block[1], 'base64').toString('utf8') : input);
    const key = privateDecrypt({
      key: readFileSync(privateKeyPath),
      padding: constants.RSA_PKCS1_OAEP_PADDING,
      oaepHash: 'sha256',
    }, Buffer.from(envelope.encryptedKey, 'base64'));
    const decipher = createDecipheriv('aes-256-gcm', key, Buffer.from(envelope.iv, 'base64'));
    decipher.setAuthTag(Buffer.from(envelope.tag, 'base64'));
    // 必须先通过 GCM 完整性校验，再写出任何明文文件。
    const plaintext = Buffer.concat([
      decipher.update(Buffer.from(envelope.data, 'base64')),
      decipher.final(),
    ]);
    const secrets = JSON.parse(plaintext.toString('utf8'));
    const store = Buffer.from(secrets.ANDROID_KEYSTORE_BASE64, 'base64');
    writeFileSync(secretsPath, plaintext, { flag: 'wx', mode: 0o600 });
    writeFileSync(storePath, store, { flag: 'wx', mode: 0o600 });
    writeFileSync(passwordPath, secrets.ANDROID_KEYSTORE_PASSWORD, { flag: 'wx', mode: 0o600 });
    console.log('已还原 videoclient-release.jks、signing-password.txt；两个原始环境变量保存在 android-signing-recovery/android-signing-secrets.json。');
  } else {
    console.error('用法：node scripts/recover-android-signing.mjs prepare | encrypt <密文输出路径或 -> | decrypt <密文文件路径>');
    process.exitCode = 1;
  }
} catch (error) {
  // 不打印异常原文，避免解析错误等情况把签名材料带入日志。
  console.error(`签名材料处理失败（${error.code ?? error.name}），请检查输入文件、公钥、私钥及文件权限。`);
  process.exitCode = 1;
}
