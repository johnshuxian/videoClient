import { createDecipheriv } from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import JSON5 from "json5";

const [
  subscriptionUrl,
  outputName = "vod-tvbox-sources.json",
  keyword = "长安",
] = process.argv.slice(2);
if (!subscriptionUrl || !/^https?:\/\//i.test(subscriptionUrl)) {
  console.error(
    '用法：npm run probe:tvbox -- "订阅地址" [输出文件.json] [搜索关键词]',
  );
  process.exit(1);
}

const outputPath = path.resolve(outputName);
const reportPath = outputPath.replace(/\.json$/i, "") + ".report.json";

async function readBytes(address, timeout = 15000) {
  // 订阅镜像和采集接口偶尔超时；网络错误重试一次，明确的 HTTP 错误直接返回。
  for (let attempt = 0; attempt < 2; attempt++) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);
    try {
      const response = await fetch(address, { signal: controller.signal });
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      return Buffer.from(await response.arrayBuffer());
    } catch (error) {
      if (attempt === 1 || /^HTTP \d+/.test(error.message)) throw error;
    } finally {
      clearTimeout(timer);
    }
  }
}

function decodeConfig(bytes) {
  // 部分 TVBox 订阅把配置藏在 JPEG 尾部，另一些使用 2423 AES-CBC 格式。
  let content =
    bytes[0] === 0xff && bytes[1] === 0xd8
      ? bytes.toString("latin1")
      : bytes
          .toString("utf8")
          .replace(/^\uFEFF/, "")
          .trim();
  for (let attempt = 0; attempt < 3; attempt++) {
    if (content.startsWith("2423")) {
      const start = content.indexOf("2324");
      if (start < 0) throw new Error("2423 配置缺少加密数据");
      const meta = Buffer.from(content, "hex").toString("utf8").toLowerCase();
      const keyStart = meta.indexOf("$#");
      const keyEnd = meta.indexOf("#$");
      if (keyStart < 0 || keyEnd < 0) throw new Error("2423 配置缺少密钥标记");
      const key = meta
        .slice(keyStart + 2, keyEnd)
        .trim()
        .padEnd(16, "0")
        .slice(0, 16);
      const iv = meta.slice(-13).trim().padEnd(16, "0").slice(0, 16);
      const decipher = createDecipheriv(
        "aes-128-cbc",
        Buffer.from(key),
        Buffer.from(iv),
      );
      const encrypted = Buffer.from(content.slice(start + 4, -26), "hex");
      content = Buffer.concat([decipher.update(encrypted), decipher.final()])
        .toString("utf8")
        .trim();
      continue;
    }
    if (!content.startsWith("{") && !content.startsWith("[")) {
      const marker = /[A-Za-z0]{8}\*\*/.exec(content);
      if (marker) {
        content = Buffer.from(
          content.slice(marker.index + marker[0].length),
          "base64",
        )
          .toString("utf8")
          .trim();
        continue;
      }
    }
    return JSON5.parse(content);
  }
  throw new Error("无法解码配置");
}

function configAddresses(address) {
  const candidates = [address];
  for (const proxy of ["https://gh-proxy.com/", "https://gh-proxy.org/"]) {
    if (!address.startsWith(proxy)) continue;
    const target = address.slice(proxy.length);
    if (target.startsWith("https:"))
      candidates.push(target.replace(/^https:\/*/, "https://"));
    else if (target.startsWith("raw.githubusercontent.com/"))
      candidates.push(`https://${target}`);
  }
  if (address.startsWith("http://"))
    candidates.push(`https://${address.slice(7)}`);
  return [...new Set(candidates)];
}

async function loadConfig(entry) {
  const errors = [];
  for (const address of configAddresses(entry.url)) {
    try {
      const data = decodeConfig(await readBytes(address));
      if (!data || typeof data !== "object")
        throw new Error("内容不是 JSON 对象");
      if (
        !Array.isArray(data.urls) &&
        !Array.isArray(data.sites) &&
        !Array.isArray(data.sources)
      )
        throw new Error("缺少 urls、sites 或 sources 数组");
      return { ...entry, fetchedUrl: address, data };
    } catch (error) {
      errors.push(`${address}: ${error.message}`);
    }
  }
  return { ...entry, error: errors.join(" | ") };
}

function sourceKey(address) {
  const url = new URL(address);
  for (const key of ["ac", "wd", "ids", "pg"]) url.searchParams.delete(key);
  const normalizedPath = url.pathname.split("/").filter(Boolean).join("/");
  return `${url.host.toLowerCase()}/${normalizedPath}?${url.searchParams.toString()}`;
}

async function requestVodJson(address) {
  const body = (await readBytes(address, 12000)).toString("utf8");
  try {
    return JSON.parse(body);
  } catch {
    throw new Error(
      `接口未返回 JSON：${body.slice(0, 50).replace(/\s+/g, " ")}`,
    );
  }
}

async function probe(candidate) {
  const searchUrl = new URL(candidate.api);
  searchUrl.searchParams.set("ac", "videolist");
  searchUrl.searchParams.set("wd", keyword);
  const result = {
    name: candidate.name,
    url: candidate.api,
    status: "",
    error: "",
  };
  try {
    const search = await requestVodJson(searchUrl.href);
    if (!Array.isArray(search.list)) throw new Error("搜索结果缺少 list 数组");
    const item = search.list.find(
      (video) => video?.vod_id != null && video?.vod_name,
    );
    if (!item) throw new Error(`关键词“${keyword}”没有搜索结果`);
    if (
      !search.list.some((video) =>
        String(video?.vod_name || "").includes(keyword),
      )
    )
      throw new Error("搜索结果未匹配关键词");

    const detailUrl = new URL(candidate.api);
    detailUrl.searchParams.set("ac", "videolist");
    detailUrl.searchParams.set("ids", String(item.vod_id));
    const detail = (await requestVodJson(detailUrl.href)).list?.[0];
    if (!detail?.vod_name) throw new Error("详情接口未返回影片");
    // 影窗目前只播放 HTTP(S) 地址，排除还需 TVBox playerContent 解析的条目。
    const playable = String(detail.vod_play_url || "")
      .split("$$$")
      .some((line) =>
        line.split("#").some((episode) => {
          const separator = episode.indexOf("$");
          const address = (
            separator < 0 ? episode : episode.slice(separator + 1)
          ).trim();
          return /^https?:\/\//i.test(address);
        }),
      );
    if (!playable) throw new Error("详情中没有可直接播放的 HTTP(S) 地址");

    result.status = "可用";
    result.searchCount = search.list.length;
    result.exampleTitle = item.vod_name;
    try {
      const categories = (await requestVodJson(candidate.api)).class;
      const names = Array.isArray(categories)
        ? categories.map((entry) => String(entry.type_name || ""))
        : [];
      const adultNames = names.filter((name) =>
        /成人|18禁|情色|福利|伦理|三级|写真|套图|无码|女优|激情/u.test(name),
      );
      result.categoryCount = names.length;
      result.adultCategoryCount = adultNames.length;
      // 单个“伦理片”分类常见于普通采集源；大量成人分类才把整个源标为成人。
      result.isAdult =
        Boolean(candidate.isAdult) ||
        /成人|18禁|🔞|情色|福利|写真|麻豆|番号/u.test(candidate.name) ||
        (adultNames.length >= 5 && adultNames.length >= names.length / 10);
    } catch (error) {
      result.categoryError = error.message;
      result.isAdult =
        Boolean(candidate.isAdult) ||
        /成人|18禁|🔞|情色|福利|写真|麻豆|番号/u.test(candidate.name);
    }
  } catch (error) {
    result.status = "不可用";
    result.error = error.message;
  }
  return result;
}

const queue = [{ name: "入口", url: subscriptionUrl, depth: 0 }];
const visited = new Set();
const configs = [];
const candidates = new Map();
let directEntries = 0;
let ruleEntries = 0;
while (queue.length) {
  const batch = queue.splice(0, 4).filter((entry) => {
    if (visited.has(entry.url)) return false;
    visited.add(entry.url);
    return true;
  });
  if (visited.size > 100)
    throw new Error("配置线路超过 100 条，已停止递归展开");
  for (const config of await Promise.all(batch.map(loadConfig))) {
    configs.push({
      name: config.name,
      url: config.url,
      fetchedUrl: config.fetchedUrl || "",
      error: config.error || "",
      siteCount: config.data?.sites?.length || 0,
    });
    if (config.error) {
      console.warn(`配置失败：${config.name}：${config.error}`);
      continue;
    }
    if (Array.isArray(config.data.urls) && config.depth < 3) {
      for (const item of config.data.urls) {
        if (typeof item?.url !== "string") continue;
        try {
          queue.push({
            name: String(item.name || item.url),
            url: new URL(item.url, config.fetchedUrl).href,
            depth: config.depth + 1,
          });
        } catch {
          /* 忽略无效的子配置地址。 */
        }
      }
    }
    const sites = Array.isArray(config.data.sites)
      ? config.data.sites
      : (config.data.sources || []).map((item) => ({
          ...item,
          api: item.url,
          type: 1,
        }));
    for (const site of sites) {
      if (
        Number(site?.type) !== 1 ||
        typeof site.api !== "string" ||
        !/^https?:\/\//i.test(site.api)
      ) {
        if (Number(site?.type) === 3) ruleEntries++;
        continue;
      }
      directEntries++;
      let key;
      let host;
      try {
        key = sourceKey(site.api);
        host = new URL(site.api).host;
      } catch {
        continue;
      }
      const candidate = {
        name: String(site.name || host).trim(),
        api: site.api,
        isAdult: site.isAdult === true,
      };
      if (!candidates.has(key)) candidates.set(key, []);
      if (
        !candidates.get(key).some((existing) => existing.api === candidate.api)
      )
        candidates.get(key).push(candidate);
    }
  }
  console.log(
    `已读取 ${configs.length} 条配置，发现 ${directEntries} 条直连接口`,
  );
}

const groups = [...candidates.values()];
const results = [];
for (let offset = 0; offset < groups.length; offset += 6) {
  const batch = groups.slice(offset, offset + 6);
  const checked = await Promise.all(
    batch.map(async (variants) => {
      // 同一接口可能同时列出 HTTP、HTTPS 或带 ac=list 的地址，优先验证 HTTPS 简洁地址。
      variants.sort(
        (a, b) =>
          Number(b.api.startsWith("https:")) -
            Number(a.api.startsWith("https:")) || a.api.length - b.api.length,
      );
      const attempts = [];
      for (const candidate of variants) {
        const result = await probe(candidate);
        attempts.push(result);
        if (result.status === "可用") break;
      }
      return {
        names: [...new Set(variants.map((item) => item.name))],
        attempts,
        selected: attempts.find((item) => item.status === "可用") || null,
      };
    }),
  );
  results.push(...checked);
  console.log(
    `已检测 ${Math.min(offset + batch.length, groups.length)}/${groups.length} 组接口`,
  );
}

const sources = results
  .filter((item) => item.selected)
  .map((item) => ({
    name: item.selected.name
      .replace(/^[^\p{L}\p{N}]+/u, "")
      .replace(/\s*\|\s*采集\s*$/u, "")
      .trim(),
    url: item.selected.url,
    isAdult: item.selected.isAdult,
  }));
await fs.mkdir(path.dirname(outputPath), { recursive: true });
await fs.writeFile(
  outputPath,
  `${JSON.stringify({ sources }, null, 2)}\n`,
  "utf8",
);
await fs.writeFile(
  reportPath,
  `${JSON.stringify(
    {
      inputUrl: subscriptionUrl,
      checkedAt: new Date().toISOString(),
      keyword,
      summary: {
        configs: configs.length,
        parsedConfigs: configs.filter((item) => !item.error).length,
        directEntries,
        uniqueEndpoints: groups.length,
        ruleEntries,
        usable: sources.length,
      },
      configs,
      results,
    },
    null,
    2,
  )}\n`,
  "utf8",
);
console.log(`完成：${sources.length} 条可用点播源 -> ${outputPath}`);
console.log(`检测详情 -> ${reportPath}`);
