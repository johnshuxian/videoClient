import { webview } from '@kit.ArkWeb';
import { http } from '@kit.NetworkKit';
import { common } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { settings } from '@kit.BasicServicesKit';
import { Proxy } from '../services/Proxy';
import { Dlna, DevicesResult, CastProgress } from '../services/Dlna';
import { SettingsStore, HistoryRow, SettingRow } from '../services/SettingsStore';

interface ProxyAddress { url: string }
interface CastResult { ok: boolean }

interface NetworkMessage {
  id: number;
  action: string;
  url: string;
  timeout: number;
  headers: Record<string, string>;
  body?: string | object;
  image?: boolean;
  filterAds?: boolean;
  deviceHost?: string;
  deviceId?: string;
  stream?: string;
  title?: string;
  isHls?: boolean;
  startPosition?: number;
  theme?: string;
  fullscreen?: boolean;
  brightness?: number;
  surface?: string;
  history?: HistoryRow;
  historyKey?: string;
  sourceUrl?: string;
  setting?: SettingRow;
}

@Entry
@Component
struct Index {
  private controller: webview.WebviewController = new webview.WebviewController();
  private port?: webview.WebMessagePort;
  private proxy = new Proxy();
  private proxyReady?: Promise<void>;
  private dlna = new Dlna();
  private light = false;
  private fullscreen = false;
  private surface = 'page';
  private oldOrientation = window.Orientation.UNSPECIFIED;
  private storeReady?: Promise<SettingsStore>;

  aboutToAppear(): void {
    this.proxyReady = this.proxy.start();
    this.dlna.onEnded = (deviceId) => {
      this.port?.postMessageEvent(JSON.stringify({ event: 'castEnded', deviceId, error: '' }));
    };
  }

  aboutToDisappear(): void {
    this.port?.close();
    this.dlna.close();
    void this.proxy.close();
  }

  onBackPress(): boolean {
    // 网页自己处理详情、设置与播放器的返回层级。
    try {
      this.port?.postMessageEvent('__harmony_back__');
      return true;
    } catch {
      // 网页通道已关闭时交由系统处理返回键。
      return false;
    }
  }

  build() {
    Column() {
      Web({ src: $rawfile('www/index.html'), controller: this.controller })
        .javaScriptAccess(true)
        .domStorageAccess(true)
        .zoomAccess(false)
        .onPageEnd(() => {
          // 页面刷新后旧通道不再对应当前页面，先释放再建立新通道。
          this.port?.close();
          const ports = this.controller.createWebMessagePorts();
          this.port = ports[1];
          this.port.onMessageEvent(async (value: webview.WebMessage) => {
            if (typeof value !== 'string') return;
            const message = JSON.parse(value) as NetworkMessage;
            try {
              let data: string | number | ProxyAddress | DevicesResult | CastProgress | CastResult | HistoryRow[] | HistoryRow | SettingRow[] | null = '';
              if (message.action === 'exitApp') {
                const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
                await context.terminateSelf();
                return;
              } else if (message.action === 'settingsList' || message.action === 'settingsSave' ||
                message.action === 'historyList' || message.action === 'historyLatestOtherSource' || message.action === 'historyUpsert' ||
                message.action === 'historyDelete' || message.action === 'historyClear') {
                if (!this.storeReady) {
                  const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
                  this.storeReady = SettingsStore.open(context);
                }
                const store = await this.storeReady;
                if (message.action === 'settingsList') data = await store.listSettings();
                else if (message.action === 'settingsSave' && message.setting) await store.saveSetting(message.setting);
                else if (message.action === 'historyList') data = await store.list();
                else if (message.action === 'historyLatestOtherSource')
                  data = await store.latestOtherSource(message.title || '', message.sourceUrl || '');
                else if (message.action === 'historyUpsert' && message.history) await store.upsert(message.history);
                else if (message.action === 'historyDelete') await store.delete(message.historyKey || '');
                else if (message.action === 'historyClear') await store.clear();
              } else if (message.action === 'request') {
                const task = http.createHttp();
                try {
                  const response = await task.request(message.url || '', {
                    method: message.body === undefined ? http.RequestMethod.GET : http.RequestMethod.POST,
                    header: message.headers,
                    extraData: message.body === undefined ? '' : JSON.stringify(message.body),
                    expectDataType: http.HttpDataType.STRING,
                    usingCache: false,
                    connectTimeout: message.timeout || 12000,
                    readTimeout: message.timeout || 12000
                  });
                  if (response.responseCode < 200 || response.responseCode >= 300)
                    throw new Error(`HTTP ${response.responseCode}`);
                  data = typeof response.result === 'string' ? response.result : JSON.stringify(response.result);
                } finally {
                  task.destroy();
                }
              } else if (message.action === 'proxyUrl') {
                await this.proxyReady;
                data = { url: await this.proxy.address(message.url || '', !!message.image, !!message.filterAds, message.deviceHost) };
              } else if (message.action === 'devices') {
                data = await this.dlna.devices();
              } else if (message.action === 'cast') {
                await this.dlna.cast(message.deviceId || '', message.stream || '', message.url || '',
                  message.title || '视频', !!message.isHls, message.startPosition || 0);
                data = { ok: true };
              } else if (message.action === 'castStop') {
                await this.dlna.stop();
              } else if (message.action === 'castProgress') {
                data = await this.dlna.progress();
              } else if (message.action === 'getBrightness' || message.action === 'setBrightness' ||
                message.action === 'resetBrightness') {
                const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
                const appWindow = await window.getLastWindow(context);
                if (message.action === 'getBrightness') {
                  const saved = settings.getValueSync(context, settings.display.SCREEN_BRIGHTNESS_STATUS, '128');
                  data = Math.max(0.05, Math.min(1, Number(saved) / 255));
                } else if (message.action === 'setBrightness') {
                  // 窗口亮度只在应用前台生效，不改动系统亮度设置。
                  await appWindow.setWindowBrightness(Math.max(0.05, Math.min(1, message.brightness ?? 0.5)));
                } else {
                  await appWindow.setWindowBrightness(-1);
                }
              } else if (message.action === 'setTheme' || message.action === 'setFullscreen' ||
                message.action === 'setSurface') {
                const context = this.getUIContext().getHostContext() as common.UIAbilityContext;
                const appWindow = await window.getLastWindow(context);
                if (message.action === 'setTheme') this.light = message.theme === 'light';
                else if (message.action === 'setSurface') this.surface = message.surface || 'page';
                else {
                  const enter = !!message.fullscreen;
                  if (enter && !this.fullscreen) {
                    this.oldOrientation = appWindow.getPreferredOrientation();
                    // 页面方向保持不变；网页里的播放器单独横向铺满屏幕。
                    await appWindow.setWindowSystemBarEnable([]);
                    await appWindow.setPreferredOrientation(window.Orientation.LOCKED);
                  } else if (!enter && this.fullscreen) {
                    await appWindow.setPreferredOrientation(this.oldOrientation);
                    await appWindow.setWindowBrightness(-1);
                  }
                  this.fullscreen = enter;
                }
                const color = this.fullscreen ? '#000000' : this.light ? '#F4F6FC' : '#0A0B10';
                let statusColor = color;
                if (!this.fullscreen && this.surface === 'settings')
                  statusColor = this.light ? '#FEFEFE' : '#111218';
                const contentColor = this.light && !this.fullscreen ? '#172B3C' : '#EAF2FA';
                // 手势导航区采用窗口底色；状态栏文字显式指定颜色，避免浅色主题下图标发灰。
                await appWindow.setWindowSystemBarProperties({ statusBarColor: statusColor,
                  navigationBarColor: color,
                  statusBarContentColor: contentColor, navigationBarContentColor: contentColor });
                await appWindow.setWindowSystemBarEnable(this.fullscreen ? [] : ['status', 'navigation']);
              }
              this.port?.postMessageEvent(JSON.stringify({
                id: message.id,
                ok: true,
                data
              }));
            } catch (error) {
              this.port?.postMessageEvent(JSON.stringify({
                id: message.id,
                ok: false,
                error: String(error)
              }));
            }
          });
          // Port 0 留给网页，Port 1 留在 ArkTS。页面刷新后重新建立通道。
          this.controller.postMessage('__init_harmony_port__', [ports[0]], '*');
        })
        .width('100%')
        .height('100%')
        .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.BOTTOM]);
    }
    .width('100%')
    .height('100%')
    // 网页背景延伸到手势条下方，底部导航仍由网页的安全区内边距保护。
    .expandSafeArea([SafeAreaType.SYSTEM], [SafeAreaEdge.BOTTOM]);
  }
}
