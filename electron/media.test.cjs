const test = require("node:test");
const assert = require("node:assert/strict");
const {
  filterAdSegments,
  parseEpisodes,
  rewriteManifest,
  videoUrlFromSharePage,
} = require("./media.cjs");

test("Apple CMS 的多线路、多集地址按原顺序解析", () => {
  const lines = parseEpisodes({
    vod_play_from: "量子$$$无尽",
    vod_play_url:
      "第1集$https://a.test/1.m3u8#第2集$https://a.test/2.m3u8$$$正片$https://b.test/movie.mp4",
  });
  assert.equal(lines.length, 2);
  assert.equal(lines[0].episodes[1].label, "第2集");
  assert.equal(lines[1].episodes[0].url, "https://b.test/movie.mp4");
});

test("分享页中的转义视频地址被解析为可播放的绝对地址", () => {
  const html =
    '<script>var main = "\\/20250625\\/movie\\/index.m3u8?sign=abc";</script>';
  assert.equal(
    videoUrlFromSharePage(html, "https://svip.ryplay13.com/share/123"),
    "https://svip.ryplay13.com/20250625/movie/index.m3u8?sign=abc",
  );
  assert.equal(
    videoUrlFromSharePage("<html></html>", "https://example.com/share/123"),
    null,
  );
});

test("HLS 子清单与分片走代理，整组广告地址被过滤", () => {
  const manifest =
    '#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n#EXTINF:60,\npart1.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:20,\nvideo/ads/pre-roll.ts\n#EXTINF:20,\nvideo/adjump/clip.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:60,\npart2.ts\n#EXT-X-ENDLIST';
  const result = rewriteManifest(
    manifest,
    "https://video.test/show/index.m3u8",
    (url) => `proxy:${url}`,
    true,
  );
  assert.match(result, /proxy:https:\/\/video.test\/show\/key.bin/);
  assert.match(result, /proxy:https:\/\/video.test\/show\/part1.ts/);
  assert.doesNotMatch(result, /pre-roll/);
  assert.doesNotMatch(result, /adjump/);
  assert.equal((result.match(/#EXTINF/g) || []).length, 2);
  assert.match(result, /#EXT-X-ENDLIST/);
});

test("末尾广告组删除后仍保留 ENDLIST", () => {
  const manifest =
    "#EXTM3U\n#EXTINF:60,\nmovie.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:40,\nvideo/ads/outro.ts\n#EXT-X-ENDLIST";
  const result = filterAdSegments(manifest);
  assert.doesNotMatch(result, /outro\.ts/);
  assert.match(result, /#EXT-X-ENDLIST/);
});

test("广告样式地址混在正片组时不按单片删除", () => {
  const manifest =
    "#EXTM3U\n#EXT-X-DISCONTINUITY\n#EXTINF:120,\nmovie.ts\n#EXTINF:5,\nads/clip.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:120,\nnext.ts\n#EXT-X-ENDLIST";
  assert.match(filterAdSegments(manifest), /ads\/clip\.ts/);
});

test("无地址特征的短片头和两长夹一短中插被过滤", () => {
  const lead =
    "#EXTM3U\n#EXT-X-DISCONTINUITY\n#EXTINF:15,\nintro.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:120,\nmovie.ts\n#EXT-X-ENDLIST";
  const middle =
    "#EXTM3U\n#EXTINF:150,\nfirst.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:20,\ninsert.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:150,\nlast.ts\n#EXT-X-ENDLIST";
  assert.doesNotMatch(filterAdSegments(lead), /intro\.ts/);
  assert.match(filterAdSegments(lead), /movie\.ts/);
  assert.doesNotMatch(filterAdSegments(middle), /insert\.ts/);
  assert.match(filterAdSegments(middle), /#EXT-X-ENDLIST/);
});

test("普通短分组和显式 DATERANGE 标记分别按原有边界处理", () => {
  const normal =
    "#EXTM3U\n#EXT-X-DISCONTINUITY\n#EXTINF:20,\na.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:20,\nb.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:20,\nc.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:20,\nd.ts\n#EXT-X-ENDLIST";
  const marked =
    '#EXTM3U\n#EXT-X-DATERANGE:CLASS="ad"\n#EXTINF:5,\nplain.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:20,\nmovie.ts\n#EXT-X-DISCONTINUITY\n#EXTINF:20,\nnext.ts\n#EXT-X-ENDLIST';
  assert.match(filterAdSegments(normal), /a\.ts/);
  assert.doesNotMatch(filterAdSegments(marked), /plain\.ts/);
  assert.match(filterAdSegments(marked), /movie\.ts/);
  assert.match(filterAdSegments(marked), /next\.ts/);
});
