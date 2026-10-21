import { http, socket } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import url from '@ohos.url';
import { lanNetwork } from './Lan';

const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/125 Safari/537.36';
const encoder = new util.TextEncoder();
const decoder = new util.TextDecoder('utf-8');
const AD_URL = /adjump|advert|\/ads?\//i;
const AD_DATE_RANGE = /\bCLASS\s*=\s*"?(?:ad|advert)/i;

interface SegmentGroup {
  start: number;
  end: number;
  disc: number;
  urls: string[];
  seconds: number;
}

function scanGroups(lines: string[]): SegmentGroup[] {
  const boundaries: number[] = [-1];
  lines.forEach((line: string, index: number) => {
    if (line.trim() === '#EXT-X-DISCONTINUITY') boundaries.push(index);
  });
  boundaries.push(lines.length);
  const groups: SegmentGroup[] = [];
  for (let groupIndex = 0; groupIndex < boundaries.length - 1; groupIndex++) {
    const start = boundaries[groupIndex] + 1;
    const end = boundaries[groupIndex + 1];
    const urls: string[] = [];
    let seconds = 0;
    for (let i = start; i < end; i++) {
      const line = lines[i].trim();
      const duration = line.match(/^#EXTINF:([\d.]+)/);
      if (duration) seconds += Number(duration[1]);
      else if (line && !line.startsWith('#')) urls.push(line);
    }
    groups.push({ start, end, disc: boundaries[groupIndex], urls, seconds });
  }
  return groups;
}

function removeGroups(lines: string[], groups: SegmentGroup[]): string[] {
  const removed: boolean[] = new Array<boolean>(lines.length).fill(false);
  for (const group of groups) {
    if (group.disc >= 0) removed[group.disc] = true;
    for (let i = group.start; i < group.end; i++) {
      // 正片可能继续使用广告段声明的密钥或初始化片段。
      const line = lines[i].trim();
      if (!line.startsWith('#EXT-X-KEY:') && !line.startsWith('#EXT-X-MAP:') &&
        line !== '#EXT-X-ENDLIST') removed[i] = true;
    }
  }
  return lines.filter((_line: string, index: number) => !removed[index]);
}

function filterHlsAdSegments(manifest: string): string {
  // 直播清单会持续变更序号，字节范围清单可能依赖前一片段的偏移，先保持原样。
  if (!manifest.includes('#EXT-X-ENDLIST') || manifest.includes('#EXT-X-BYTERANGE')) return manifest;

  let lines = manifest.replace(/\r/g, '').split('\n');
  let groups = scanGroups(lines);
  // 与 LibreTV 一样，地址命中时只有整组分片都命中才删除。
  lines = removeGroups(lines, groups.filter((group: SegmentGroup) =>
    group.disc >= 0 && group.urls.length > 0 && group.urls.every((segment: string) => AD_URL.test(segment))));

  groups = scanGroups(lines);
  // 两个至少 120 秒的长段之间，才把不超过 30 秒的短段判为中插。
  lines = removeGroups(lines, groups.filter((group: SegmentGroup, index: number) =>
    index > 0 && group.urls.length > 0 && group.seconds > 0 && group.seconds <= 30 &&
    groups[index - 1].seconds >= 120 && (groups[index + 1]?.seconds || 0) >= 120));

  groups = scanGroups(lines);
  const lead = groups[1];
  if (groups[0]?.urls.length === 0 && lead && lead.urls.length > 0 && groups.length > 2 &&
    lead.seconds > 0 && lead.seconds <= 90 && lead.urls.length <= 20) {
    const others = groups.slice(2).map((group: SegmentGroup) => group.urls.length).sort((a: number, b: number) => a - b);
    const median = others[Math.floor(others.length / 2)];
    if (groups.length < 5 || Math.abs(lead.urls.length - median) > Math.max(3, median * 0.5))
      lines = removeGroups(lines, [lead]);
  }

  // 显式标记最后处理，避免删掉片头广告后把短正片误判为新的片头。
  const output: string[] = [];
  let dateRangeAd = false;
  let cueAd = false;
  let segmentStart = -1;
  for (const line of lines) {
    if (line.startsWith('#EXT-X-DATERANGE') && AD_DATE_RANGE.test(line)) {
      dateRangeAd = true;
      continue;
    }
    if (line.startsWith('#EXT-X-CUE-OUT')) {
      cueAd = true;
      continue;
    }
    if (line.startsWith('#EXT-X-CUE-IN')) {
      cueAd = false;
      continue;
    }
    if (line === '#EXT-X-DISCONTINUITY') dateRangeAd = false;
    if (line.startsWith('#EXTINF')) segmentStart = output.length;
    if (line && !line.startsWith('#') && segmentStart >= 0) {
      if (dateRangeAd || cueAd) output.splice(segmentStart);
      else output.push(line);
      segmentStart = -1;
    } else output.push(line);
  }
  lines = output;
  const firstSegment = lines.findIndex((line: string) => !!line.trim() && !line.trim().startsWith('#'));
  // 所有分片都被判为广告时回退原文，避免返回空清单。
  if (firstSegment < 0) return manifest;
  return lines.filter((line: string, index: number) =>
    index >= firstSegment || line.trim() !== '#EXT-X-DISCONTINUITY').join('\n');
}

export class Proxy {
  private server = socket.constructTCPSocketServerInstance();
  private token = util.generateRandomUUID();
  private port = 0;

  async start(): Promise<void> {
    try {
      await this.server.listen({ address: '0.0.0.0', port: 0 });
      this.port = (await this.server.getLocalAddress()).port || 0;
    } catch (error) {
      throw new Error(`启动本机代理失败：${String(error)}`);
    }
    this.server.on('connect', (client) => {
      let request = '';
      client.on('message', (event) => {
        request += decoder.decodeToString(new Uint8Array(event.message));
        if (request.length > 16384) {
          void client.close();
          return;
        }
        if (!request.includes('\r\n\r\n')) return;
        client.off('message');
        // 页面滚动时 ArkWeb 会取消旧图片请求，连接提前关闭属于正常情况。
        void this.respond(client, request).catch(() => {}).finally(() => client.close());
      });
    });
  }

  async close(): Promise<void> {
    try {
      await this.server.close();
    } catch {
      // 启动失败时套接字可能尚未打开。
    }
  }

  async address(target: string, image: boolean, filterAds: boolean, deviceHost?: string): Promise<string> {
    let parsed: url.URL;
    try {
      parsed = url.URL.parseURL(target);
    } catch {
      throw new Error('代理地址无效');
    }
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') throw new Error('代理地址无效');
    if (!this.port) throw new Error('本地代理未启动');
    let host = '127.0.0.1';
    if (deviceHost) {
      host = (await lanNetwork()).address;
    }
    return `http://${host}:${this.port}/${this.token}/${image ? 'image' : 'stream'}?url=${encodeURIComponent(target)}&ads=${filterAds ? '1' : '0'}`;
  }

  private async respond(client: socket.TCPSocketConnection, request: string): Promise<void> {
    let sentHeaders = false;
    const first = request.split('\r\n', 1)[0].split(' ');
    if (first.length < 2 || (first[0] !== 'GET' && first[0] !== 'HEAD')) {
      await this.error(client, 405);
      return;
    }
    let local: url.URL;
    try {
      local = url.URL.parseURL(first[1], 'http://127.0.0.1');
    } catch {
      await this.error(client, 400);
      return;
    }
    const image = local.pathname === `/${this.token}/image`;
    if (!image && local.pathname !== `/${this.token}/stream`) {
      await this.error(client, 404);
      return;
    }
    const target = local.params.get('url') || '';
    let parsed: url.URL;
    try {
      parsed = url.URL.parseURL(target);
    } catch {
      await this.error(client, 400);
      return;
    }
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      await this.error(client, 400);
      return;
    }
    const range = request.match(/^Range:\s*([^\r\n]+)/im)?.[1];
    const headers: Record<string, string> = {
      'User-Agent': USER_AGENT,
      'Referer': parsed.hostname.endsWith('doubanio.com') ? 'https://movie.douban.com/' : parsed.origin + '/',
      'Accept-Encoding': 'identity'
    };
    if (range) headers['Range'] = range;
    const task = http.createHttp();
    try {
      const manifest = !image && /\.m3u8(?:$|[?#])/i.test(target);
      if (manifest) {
        const response = await task.request(target, {
          header: headers, expectDataType: http.HttpDataType.STRING, usingCache: false,
          connectTimeout: 12000, readTimeout: 60000
        });
        if (response.responseCode < 200 || response.responseCode >= 300) {
          await this.error(client, response.responseCode);
          return;
        }
        const base = url.URL.parseURL(target);
        // 电视请求到达时取该连接的本机地址，HLS 分片才能继续从手机读取。
        const host = (await client.getLocalAddress()).address;
        const original = String(response.result);
        const filtered = local.params.get('ads') === '1' ? filterHlsAdSegments(original) : original;
        const rewritten = filtered.replace(/URI="([^"]+)"|^(?!#|\s*$)(.+)$/gm, (match, nested: string, line: string) => {
          const source = url.URL.parseURL(nested || line, base).toString();
          const address = `http://${host}:${this.port}/${this.token}/stream?url=${encodeURIComponent(source)}&ads=${local.params.get('ads') || '0'}`;
          return nested ? `URI="${address}"` : address;
        });
        const body = encoder.encodeInto(rewritten);
        await client.send({ data: `HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: ${body.byteLength}\r\nConnection: close\r\n\r\n` });
        if (first[0] !== 'HEAD') await client.send({ data: body.buffer });
        return;
      }

      // 流式传送视频和图片，避免把整部影片放进 ArkTS 内存；Range 原样交给上游。
      let writes: Promise<void> = Promise.resolve();
      let chunked = true;
      task.on('headersReceive', (raw) => {
        const header = raw as Record<string, string>;
        const status = Number(String(header['status-line'] || '').match(/\s(\d{3})\s/)?.[1] || 200);
        const type = header['content-type'] || 'application/octet-stream';
        const contentRange = header['content-range'] ? `Content-Range: ${header['content-range']}\r\n` : '';
        const acceptRanges = header['accept-ranges'] ? `Accept-Ranges: ${header['accept-ranges']}\r\n` : '';
        // 电视通过 Range 跳转 MP4 时通常需要明确的响应长度。
        const length = header['content-length'];
        chunked = !/^\d+$/.test(length || '');
        const framing = chunked ? 'Transfer-Encoding: chunked\r\n' : `Content-Length: ${length}\r\n`;
        sentHeaders = true;
        const cache = image && status >= 200 && status < 300 ? 'private, max-age=86400' : 'no-store';
        writes = writes.then(() => client.send({ data: `HTTP/1.1 ${status} ${status === 206 ? 'Partial Content' : 'OK'}\r\nContent-Type: ${type}\r\n${contentRange}${acceptRanges}Access-Control-Allow-Origin: *\r\nCache-Control: ${cache}\r\n${framing}Connection: close\r\n\r\n` }));
      });
      task.on('dataReceive', (chunk) => {
        if (first[0] === 'HEAD') return;
        writes = writes.then(async () => {
          if (chunked) await client.send({ data: `${chunk.byteLength.toString(16)}\r\n` });
          await client.send({ data: chunk });
          if (chunked) await client.send({ data: '\r\n' });
        });
      });
      await task.requestInStream(target, {
        header: headers, usingCache: false, connectTimeout: 12000, readTimeout: 60000
      });
      if (!sentHeaders) {
        await this.error(client, 502);
      } else {
        await writes;
        if (chunked && first[0] !== 'HEAD') await client.send({ data: '0\r\n\r\n' });
      }
    } catch {
      // 一旦已发送响应头，连接关闭会通知播放器重试；此时不能再写第二份 HTTP 响应。
      if (!sentHeaders) await this.error(client, 502);
    } finally {
      task.destroy();
    }
  }

  private async error(client: socket.TCPSocketConnection, status: number): Promise<void> {
    try {
      await client.send({ data: `HTTP/1.1 ${status} Error\r\nContent-Length: 0\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n` });
    } catch {
      // 对端已断开时，错误响应也无法再发送。
    }
  }
}
