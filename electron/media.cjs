const AD_HINT =
  /(?:^|[\/_?&=-])(ad|ads|advert|advertise|commercial|preroll)(?:[\/_?&=.-]|$)/i;

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

// HLS 相对路径必须按原始清单地址解析，电视和本机播放器才能请求同一个代理。
function rewriteManifest(body, baseUrl, proxyUrl, filterAds) {
  const lines = body.replace(/\r/g, "").split("\n");
  let skipSegment = false;
  let adBreak = false;
  const output = [];
  for (const line of lines) {
    if (
      line.startsWith("#EXT-X-DATERANGE") &&
      /CLASS="?(?:ad|advert)/i.test(line)
    ) {
      adBreak = filterAds;
      continue;
    }
    if (line.startsWith("#EXT-X-DISCONTINUITY")) {
      adBreak = false;
      output.push(line);
      continue;
    }
    if (line.startsWith("#EXTINF")) {
      skipSegment = filterAds && adBreak;
      if (!skipSegment) output.push(line);
      continue;
    }
    if (line && !line.startsWith("#")) {
      const absolute = new URL(line, baseUrl).href;
      if (!skipSegment && !(filterAds && AD_HINT.test(absolute)))
        output.push(proxyUrl(absolute));
      else if (output.at(-1)?.startsWith("#EXTINF")) output.pop();
      skipSegment = false;
      continue;
    }
    if (line.includes('URI="')) {
      output.push(
        line.replace(
          /URI="([^"]+)"/g,
          (_, uri) => `URI="${proxyUrl(new URL(uri, baseUrl).href)}"`,
        ),
      );
    } else if (!skipSegment) output.push(line);
  }
  // 删除过滤后悬空的 discontinuity，避免部分电视拒绝清单。
  return output
    .filter(
      (line, index) =>
        !line.startsWith("#EXT-X-DISCONTINUITY") ||
        (index > 0 &&
          index < output.length - 1 &&
          !output[index - 1].startsWith("#EXT-X-DISCONTINUITY")),
    )
    .join("\n");
}

module.exports = { parseEpisodes, rewriteManifest };
