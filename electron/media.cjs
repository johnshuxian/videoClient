const AD_URL = /adjump|advert|\/ads?\//i;

// 按 DISCONTINUITY 分组；只有整组地址都像广告时才按地址删除，避免误删混在正片中的分片。
function scanGroups(lines) {
  const boundaries = [-1];
  lines.forEach((line, index) => {
    if (line.trim() === "#EXT-X-DISCONTINUITY") boundaries.push(index);
  });
  boundaries.push(lines.length);
  return boundaries.slice(0, -1).map((boundary, index) => {
    const start = boundary + 1;
    const end = boundaries[index + 1];
    const segments = [];
    let seconds = 0;
    for (let i = start; i < end; i++) {
      const duration = lines[i].trim().match(/^#EXTINF:([\d.]+)/);
      if (duration) seconds += Number(duration[1]);
      else if (lines[i].trim() && !lines[i].trim().startsWith("#"))
        segments.push(lines[i].trim());
    }
    return { start, end, disc: boundary, segments, seconds };
  });
}

function removeGroups(lines, groups) {
  const removed = new Set();
  for (const group of groups) {
    if (group.disc >= 0) removed.add(group.disc);
    for (let i = group.start; i < group.end; i++) {
      // 正片可能继续使用广告段声明的密钥或初始化片段。
      if (
        !/^#EXT-X-(KEY|MAP):/.test(lines[i].trim()) &&
        lines[i].trim() !== "#EXT-X-ENDLIST"
      )
        removed.add(i);
    }
  }
  return lines.filter((_, index) => !removed.has(index));
}

function filterAdSegments(body) {
  let lines = body.replace(/\r/g, "").split("\n");
  let groups = scanGroups(lines);
  lines = removeGroups(
    lines,
    groups.filter(
      (group) =>
        group.disc >= 0 &&
        group.segments.length > 0 &&
        group.segments.every((url) => AD_URL.test(url)),
    ),
  );

  // LibreTV 的两长夹一短规则：只删除前后组均至少 120 秒的 30 秒以内中插。
  groups = scanGroups(lines);
  lines = removeGroups(
    lines,
    groups.filter(
      (group, index) =>
        index > 0 &&
        group.segments.length > 0 &&
        group.seconds > 0 &&
        group.seconds <= 30 &&
        groups[index - 1].seconds >= 120 &&
        groups[index + 1]?.seconds >= 120,
    ),
  );

  groups = scanGroups(lines);
  const lead = groups[1];
  if (
    groups[0]?.segments.length === 0 &&
    lead?.segments.length > 0 &&
    groups[2] &&
    lead.seconds > 0 &&
    lead.seconds <= 90 &&
    lead.segments.length <= 20
  ) {
    const otherSizes = groups
      .slice(2)
      .map((group) => group.segments.length)
      .sort((a, b) => a - b);
    const median = otherSizes[Math.floor(otherSizes.length / 2)];
    if (
      groups.length < 5 ||
      Math.abs(lead.segments.length - median) > Math.max(3, median * 0.5)
    )
      lines = removeGroups(lines, [lead]);
  }

  // 显式标记最后处理，避免删掉片头广告后把紧邻的短正片误判为另一个片头。
  let adBreak = false;
  let segmentStart = -1;
  const explicit = [];
  for (const line of lines) {
    if (
      line.startsWith("#EXT-X-DATERANGE") &&
      /CLASS="?(?:ad|advert)/i.test(line)
    ) {
      adBreak = true;
      continue;
    }
    if (line.trim() === "#EXT-X-DISCONTINUITY") adBreak = false;
    if (line.startsWith("#EXTINF")) segmentStart = explicit.length;
    if (line && !line.startsWith("#") && segmentStart >= 0) {
      if (adBreak) explicit.splice(segmentStart);
      else explicit.push(line);
      segmentStart = -1;
    } else explicit.push(line);
  }
  lines = explicit;

  const firstSegment = lines.findIndex(
    (line) => line.trim() && !line.trim().startsWith("#"),
  );
  // 极端清单全部被识别为广告时仍保留原始内容，优先保证可以播放。
  if (firstSegment < 0) return body;
  lines = lines.filter(
    (line, index) =>
      index >= firstSegment || line.trim() !== "#EXT-X-DISCONTINUITY",
  );
  return lines.join("\n");
}

function parseEpisodes(vod) {
  const names = String(vod.vod_play_from || "").split("$$$");
  const groups = String(vod.vod_play_url || "").split("$$$");
  return groups.flatMap((group, index) => {
    const episodes = group
      .split("#")
      .map((entry, episodeIndex) => {
        const separator = entry.indexOf("$");
        const label =
          separator < 0
            ? `第 ${episodeIndex + 1} 集`
            : entry.slice(0, separator);
        const url = separator < 0 ? entry : entry.slice(separator + 1);
        return /^https?:\/\//i.test(url)
          ? { label: label || `第 ${episodeIndex + 1} 集`, url }
          : null;
      })
      .filter(Boolean);
    return episodes.length
      ? [{ name: names[index] || `线路 ${index + 1}`, episodes }]
      : [];
  });
}

// 如意等分享页把实际视频地址放在页面的 main 变量中，地址可能含有 JS 转义的斜杠。
function videoUrlFromSharePage(html, pageUrl) {
  const match = html.match(/\bvar\s+main\s*=\s*("(?:\\.|[^"\\])*")/);
  if (!match) return null;
  const url = new URL(JSON.parse(match[1]), pageUrl);
  return ["http:", "https:"].includes(url.protocol) ? url.href : null;
}

// HLS 相对路径必须按原始清单地址解析，电视和本机播放器才能请求同一个代理。
function rewriteManifest(body, baseUrl, proxyUrl, filterAds) {
  const lines = (filterAds ? filterAdSegments(body) : body)
    .replace(/\r/g, "")
    .split("\n");
  const output = [];
  for (const line of lines) {
    if (line && !line.startsWith("#")) {
      const absolute = new URL(line, baseUrl).href;
      output.push(proxyUrl(absolute));
      continue;
    }
    if (line.includes('URI="')) {
      output.push(
        line.replace(
          /URI="([^"]+)"/g,
          (_, uri) => `URI="${proxyUrl(new URL(uri, baseUrl).href)}"`,
        ),
      );
    } else output.push(line);
  }
  return output.join("\n");
}

module.exports = {
  filterAdSegments,
  parseEpisodes,
  rewriteManifest,
  videoUrlFromSharePage,
};
