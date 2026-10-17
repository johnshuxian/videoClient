import { http, socket } from '@kit.NetworkKit';
import { util } from '@kit.ArkTS';
import url from '@ohos.url';
import { lanNetwork } from './Lan';

interface Renderer {
  id: string;
  name: string;
  host: string;
  control: string;
  serviceType: string;
}

export interface DlnaDevice {
  id: string;
  name: string;
  host: string;
  type: string;
  paired: boolean;
}

export interface DevicesResult {
  items: DlnaDevice[];
  warnings: string[];
}

export interface CastProgress {
  deviceId?: string;
  url?: string;
  position?: number;
  duration?: number;
}

const SERVICE = 'urn:schemas-upnp-org:service:AVTransport:';
const decoder = new util.TextDecoder('utf-8');

function xml(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&apos;');
}

function field(body: string, name: string): string {
  // UPnP 响应可能为元素加命名空间前缀；只提取这几个协议规定的文本字段。
  const match = body.match(new RegExp(`<(?:[\\w-]+:)?${name}(?:\\s[^>]*)?>([\\s\\S]*?)<\\/(?:[\\w-]+:)?${name}>`, 'i'));
  return (match?.[1] || '').replace(/&amp;/g, '&').replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").trim();
}

function seconds(value: string): number {
  const parts = value.split(':').map(Number);
  return parts.length === 3 && parts.every(Number.isFinite)
    ? parts[0] * 3600 + parts[1] * 60 + parts[2] : 0;
}

export class Dlna {
  private renderers = new Map<string, Renderer>();
  private current?: Renderer;
  private currentUrl = '';
  private seenPlaying = false;
  private monitor?: number;
  onEnded?: (deviceId: string) => void;

  async devices(): Promise<DevicesResult> {
    const udp = socket.constructUDPSocketInstance();
    const locations = new Set<string>();
    try {
      const lan = await lanNetwork();
      udp.on('message', (event) => {
        const response = decoder.decodeToString(new Uint8Array(event.message));
        const location = response.match(/^location:\s*(\S+)/im)?.[1];
        if (location && locations.size < 40) locations.add(location);
      });
      await udp.bind({ address: '0.0.0.0', port: 0 });
      await lan.handle.bindSocket(udp);
      const targets = ['urn:schemas-upnp-org:device:MediaRenderer:1',
        'urn:schemas-upnp-org:service:AVTransport:1', 'ssdp:all'];
      // 三轮 M-SEARCH 可容忍电视漏收一次组播；响应回到此 UDP 端口。
      for (let round = 0; round < 3; round++) {
        for (const target of targets) {
          await udp.send({
            data: `M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: "ssdp:discover"\r\nMX: 2\r\nST: ${target}\r\n\r\n`,
            address: { address: '239.255.255.250', port: 1900 }
          });
        }
        await new Promise<void>((resolve) => setTimeout(resolve, 1000));
      }
      await new Promise<void>((resolve) => setTimeout(resolve, 1000));
    } finally {
      await udp.close();
    }
    const found = await Promise.all(Array.from(locations).slice(0, 20).map((location) => this.describe(location)));
    const items: DlnaDevice[] = [];
    for (const renderer of found) {
      if (!renderer) continue;
      this.renderers.set(renderer.id, renderer);
      items.push({ id: renderer.id, name: renderer.name, host: renderer.host, type: 'dlna', paired: true });
    }
    const warnings = items.length ? [] : [locations.size
      ? '收到设备响应，但没有找到支持 DLNA 视频播放的设备。'
      : '未收到 SSDP 响应；请确认手机和电视在同一局域网，且路由器未隔离设备或屏蔽组播。'];
    return { items, warnings };
  }

  private async describe(location: string): Promise<Renderer | undefined> {
    const task = http.createHttp();
    try {
      const address = url.URL.parseURL(location);
      if (address.protocol !== 'http:' && address.protocol !== 'https:') return undefined;
      const response = await task.request(location, { connectTimeout: 1800, readTimeout: 1800,
        expectDataType: http.HttpDataType.STRING, usingCache: false });
      if (response.responseCode !== 200) return undefined;
      const body = String(response.result);
      const services = body.match(/<(?:[\w-]+:)?service>[^]*?<\/(?:[\w-]+:)?service>/gi) || [];
      for (const service of services) {
        const serviceType = field(service, 'serviceType');
        if (!serviceType.startsWith(SERVICE)) continue;
        const base = field(body, 'URLBase') || location;
        return { id: location, name: field(body, 'friendlyName') || address.hostname,
          host: address.hostname, control: url.URL.parseURL(field(service, 'controlURL'), base).toString(), serviceType };
      }
    } catch {
      // 一台离线设备不能阻止其他电视出现在列表中。
    } finally {
      task.destroy();
    }
    return undefined;
  }

  async cast(deviceId: string, stream: string, original: string, title: string, isHls: boolean): Promise<void> {
    const renderer = this.renderers.get(deviceId);
    if (!renderer) throw new Error('电视已失效，请重新搜索');
    try {
      await this.stop();
    } catch {
      // 上一次连接的电视已离线时，仍允许切换到新设备。
    }
    const mime = isHls ? 'application/vnd.apple.mpegurl' : 'video/mp4';
    const didl = `<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="0" parentID="-1" restricted="1"><dc:title>${xml(title)}</dc:title><upnp:class>object.item.videoItem</upnp:class><res protocolInfo="http-get:*:${mime}:*">${xml(stream)}</res></item></DIDL-Lite>`;
    await this.soap(renderer, 'SetAVTransportURI', `<CurrentURI>${xml(stream)}</CurrentURI><CurrentURIMetaData>${xml(didl)}</CurrentURIMetaData>`);
    await this.soap(renderer, 'Play', '<Speed>1</Speed>');
    this.current = renderer;
    this.currentUrl = original;
    this.seenPlaying = false;
    this.monitor = setInterval(() => { void this.checkEnded(renderer); }, 5000);
  }

  async stop(): Promise<void> {
    const previous = this.current;
    this.current = undefined;
    if (this.monitor !== undefined) clearInterval(this.monitor);
    this.monitor = undefined;
    if (previous) await this.soap(previous, 'Stop', '');
  }

  async progress(): Promise<CastProgress> {
    if (!this.current) return {};
    try {
      const response = await this.soap(this.current, 'GetPositionInfo', '');
      return { deviceId: this.current.id, url: this.currentUrl,
        position: seconds(field(response, 'RelTime')), duration: seconds(field(response, 'TrackDuration')) };
    } catch {
      return {};
    }
  }

  close(): void {
    if (this.monitor !== undefined) clearInterval(this.monitor);
  }

  private async checkEnded(renderer: Renderer): Promise<void> {
    if (this.current !== renderer) return;
    try {
      const state = field(await this.soap(renderer, 'GetTransportInfo', ''), 'CurrentTransportState');
      if (state === 'PLAYING') this.seenPlaying = true;
      if (this.current === renderer && this.seenPlaying &&
          (state === 'STOPPED' || state === 'NO_MEDIA_PRESENT')) {
        this.current = undefined;
        if (this.monitor !== undefined) clearInterval(this.monitor);
        this.monitor = undefined;
        this.onEnded?.(renderer.id);
      }
    } catch {
      // 少数电视不提供播放状态，已成功投屏仍可继续播放。
    }
  }

  private async soap(renderer: Renderer, action: string, args: string): Promise<string> {
    const body = `<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:${action} xmlns:u="${renderer.serviceType}"><InstanceID>0</InstanceID>${args}</u:${action}></s:Body></s:Envelope>`;
    const task = http.createHttp();
    try {
      const response = await task.request(renderer.control, { method: http.RequestMethod.POST,
        header: { 'Content-Type': 'text/xml; charset=utf-8', 'SOAPACTION': `"${renderer.serviceType}#${action}"` },
        extraData: body, expectDataType: http.HttpDataType.STRING,
        usingCache: false, connectTimeout: 4000, readTimeout: 5000 });
      if (response.responseCode !== 200) throw new Error(`电视返回 HTTP ${response.responseCode}`);
      return String(response.result);
    } finally {
      task.destroy();
    }
  }
}
