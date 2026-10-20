import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import JSON5 from "json5";

const project = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const templatePath = path.join(project, "harmony", "build-profile.template.json");
const profilePath = process.env.HARMONY_BUILD_PROFILE_PATH ||
  path.join(project, "harmony", "build-profile.json5");
const signingDir = process.env.HARMONY_SIGNING_DIR || path.join(project, "harmony-signing");
const certificateSha256 = "50599d171bf509a40dfa214b5bcd366b2b6ff9c762aa67781a600b8b79c4c5c6";

for (const file of ["signing.cer", "signing.p7b", "signing.p12", "build-profile.json5"])
  if (!fs.existsSync(path.join(signingDir, file))) throw new Error(`签名包缺少 ${file}`);
if (!fs.existsSync(path.join(signingDir, "material")))
  throw new Error("签名包缺少 DevEco 密码加密材料 material 目录");

// 固定公钥证书，避免误上传另一套签名导致后续 HAP 无法覆盖安装。
const actualSha256 = createHash("sha256")
  .update(fs.readFileSync(path.join(signingDir, "signing.cer"))).digest("hex");
if (actualSha256 !== certificateSha256)
  throw new Error("鸿蒙证书与当前真机版本的签名证书不一致");

const profile = JSON.parse(fs.readFileSync(templatePath, "utf8"));
const localProfile = JSON5.parse(fs.readFileSync(path.join(signingDir, "build-profile.json5"), "utf8"));
const signing = localProfile.app.signingConfigs[0];
profile.app.signingConfigs = [{
  name: signing.name,
  type: signing.type,
  material: {
    certpath: path.join(signingDir, "signing.cer"),
    keyAlias: signing.material.keyAlias,
    keyPassword: signing.material.keyPassword,
    profile: path.join(signingDir, "signing.p7b"),
    signAlg: signing.material.signAlg,
    storeFile: path.join(signingDir, "signing.p12"),
    storePassword: signing.material.storePassword,
  },
}];
fs.writeFileSync(profilePath, JSON.stringify(profile, null, 2) + "\n", { mode: 0o600 });
console.log("鸿蒙签名配置已从签名包还原，证书指纹匹配。");
