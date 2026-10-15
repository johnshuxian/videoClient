const test = require("node:test");
const assert = require("node:assert/strict");
const {
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

test("HLS 子清单与分片走代理，带广告标记的片段被过滤", () => {
  const manifest =
    '#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="key.bin"\n#EXTINF:5,\npart1.ts\n#EXTINF:3,\nads/pre-roll.ts\n#EXTINF:5,\npart2.ts\n#EXT-X-ENDLIST';
  const result = rewriteManifest(
    manifest,
    "https://video.test/show/index.m3u8",
    (url) => `proxy:${url}`,
    true,
  );
  assert.match(result, /proxy:https:\/\/video.test\/show\/key.bin/);
  assert.match(result, /proxy:https:\/\/video.test\/show\/part1.ts/);
  assert.doesNotMatch(result, /pre-roll/);
  assert.equal((result.match(/#EXTINF/g) || []).length, 2);
});
