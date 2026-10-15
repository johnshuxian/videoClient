import { useEffect, useRef, useState } from "react";
import Hls from "hls.js";
import {
  ArrowLeft,
  ArrowRight,
  Check,
  ChevronDown,
  Clock3,
  Clapperboard,
  ExternalLink,
  Film,
  History,
  LoaderCircle,
  MonitorPlay,
  Play,
  RefreshCw,
  Search,
  Settings2,
  ShieldCheck,
  SkipForward,
  Sparkles,
  Square,
  Sun,
  Moon,
  Tv,
  Wifi,
  X,
} from "lucide-react";
import type {
  CatalogItem,
  Device,
  HistoryItem,
  SearchItem,
  Source,
  SourceHealth,
  VodDetail,
} from "./types";

const MOVIE_TAGS = [
  "热门",
  "最新",
  "经典",
  "豆瓣高分",
  "冷门佳片",
  "华语",
  "欧美",
  "韩国",
  "日本",
  "动画",
];
const TV_TAGS = [
  "热门",
  "最新",
  "国产剧",
  "美剧",
  "英剧",
  "日剧",
  "韩剧",
  "日本动画",
  "纪录片",
];
function readStorage<T>(key: string, fallback: T): T {
  try {
    return JSON.parse(localStorage.getItem(key) || "") as T;
  } catch {
    return fallback;
  }
}

function saveStorage(key: string, value: unknown) {
  localStorage.setItem(key, JSON.stringify(value));
}

function useProxyImage(original: string) {
  const [url, setUrl] = useState("");
  useEffect(() => {
    let active = true;
    setUrl("");
    if (original) {
      window.videoAPI.imageUrl(original).then((proxied) => {
        if (active) setUrl(proxied);
      });
    }
    return () => {
      active = false;
    };
  }, [original]);
  return url;
}

function PosterImage({
  src,
  alt = "",
  loading = "lazy",
}: {
  src: string;
  alt?: string;
  loading?: "lazy" | "eager";
}) {
  const url = useProxyImage(src);
  return url ? <img src={url} alt={alt} loading={loading} /> : null;
}

function HeroPoster({ item }: { item: CatalogItem }) {
  const image = useProxyImage(item.poster);
  return (
    <div
      className="hero-poster"
      style={
        image
          ? {
              backgroundImage: `linear-gradient(90deg,#111b2b 0%,transparent 45%),url("${image}")`,
            }
          : undefined
      }
    >
      <div className="hero-poster-label">
        <span>今日推荐</span>
        <strong>{item.title}</strong>
        {item.rating && <small>★ {item.rating} 豆瓣评分</small>}
      </div>
    </div>
  );
}

function Player({
  url,
  filterAds,
  resume,
  onProgress,
  onEnded,
}: {
  url: string;
  filterAds: boolean;
  resume: number;
  onProgress: (position: number, duration: number) => void;
  onEnded: () => void;
}) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    let hls: Hls | undefined;
    let cancelled = false;
    setError("");
    const restorePosition = () => {
      if (resume > 5 && resume < video.duration - 15)
        video.currentTime = resume;
    };
    window.videoAPI
      .streamUrl(url, filterAds)
      .then((stream) => {
        if (cancelled) return;
        if (/\.m3u8(?:$|[?#])/i.test(url) && Hls.isSupported()) {
          hls = new Hls({ maxBufferLength: 40, enableWorker: true });
          hls.loadSource(stream);
          hls.attachMedia(video);
          hls.on(Hls.Events.ERROR, (_, data) => {
            if (data.fatal) setError(`视频加载失败：${data.details}`);
          });
        } else {
          video.src = stream;
        }
        video.addEventListener("loadedmetadata", restorePosition, {
          once: true,
        });
        video.play().catch(() => {
          /* 用户可以使用播放器按钮手动开始。 */
        });
      })
      .catch((error) => setError(error.message));
    return () => {
      cancelled = true;
      video.removeEventListener("loadedmetadata", restorePosition);
      hls?.destroy();
      video.pause();
      video.removeAttribute("src");
      video.load();
    };
  }, [url, filterAds]);

  return (
    <div className="video-shell">
      <video
        ref={videoRef}
        controls
        autoPlay
        playsInline
        onTimeUpdate={(event) =>
          onProgress(
            event.currentTarget.currentTime,
            event.currentTarget.duration || 0,
          )
        }
        onEnded={onEnded}
        onError={() => setError("该线路暂时无法播放，请切换线路或集数。")}
      />
      {error && <div className="video-error">{error}</div>}
    </div>
  );
}

export default function App() {
  const [section, setSection] = useState<"home" | "search" | "history">("home");
  const [type, setType] = useState<"movie" | "tv">("movie");
  const [tag, setTag] = useState("热门");
  const [page, setPage] = useState(0);
  const [catalog, setCatalog] = useState<CatalogItem[]>([]);
  const [catalogLoading, setCatalogLoading] = useState(true);
  const [catalogError, setCatalogError] = useState("");
  const [catalogHasMore, setCatalogHasMore] = useState(true);
  const [catalogReload, setCatalogReload] = useState(0);
  const [sources, setSources] = useState<Source[]>(
    readStorage("vod-sources", []),
  );
  const [enabled, setEnabled] = useState<string[]>(
    readStorage("vod-enabled", []),
  );
  const [subscriptionUrl, setSubscriptionUrl] = useState(
    readStorage("vod-subscription-url", ""),
  );
  const [subscriptionInput, setSubscriptionInput] = useState(subscriptionUrl);
  const [sourceBusy, setSourceBusy] = useState(false);
  const [sourceError, setSourceError] = useState("");
  const [sourceHealth, setSourceHealth] = useState<
    Record<string, SourceHealth>
  >({});
  const [testing, setTesting] = useState(false);
  const [tested, setTested] = useState(0);
  const [testStopped, setTestStopped] = useState(false);
  const [theme, setTheme] = useState<"dark" | "light">(
    readStorage("color-theme", "dark"),
  );
  const [filterAds, setFilterAds] = useState(readStorage("filter-ads", true));
  const [autoplay, setAutoplay] = useState(readStorage("autoplay", true));
  const [history, setHistory] = useState<HistoryItem[]>(
    readStorage("history", []),
  );
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [searched, setSearched] = useState("");
  const [results, setResults] = useState<SearchItem[]>([]);
  const [failed, setFailed] = useState<string[]>([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const [searchProgress, setSearchProgress] = useState(0);
  const [selected, setSelected] = useState<SearchItem | null>(null);
  const [detail, setDetail] = useState<VodDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState("");
  const [line, setLine] = useState(0);
  const [episode, setEpisode] = useState<number | null>(null);
  const [devices, setDevices] = useState<Device[]>([]);
  const [castOpen, setCastOpen] = useState(false);
  const [castBusy, setCastBusy] = useState(false);
  const [castError, setCastError] = useState("");
  const [castWarnings, setCastWarnings] = useState<string[]>([]);
  const [casting, setCasting] = useState("");
  const [pairingDevice, setPairingDevice] = useState<Device | null>(null);
  const [airplayPin, setAirplayPin] = useState("");
  const searchSerial = useRef(0);
  const testSerial = useRef(0);
  const progressSaved = useRef(0);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    saveStorage("color-theme", theme);
    window.videoAPI.setTheme(theme);
  }, [theme]);

  useEffect(() => {
    let cancelled = false;
    setCatalogLoading(true);
    setCatalogError("");
    window.videoAPI
      .catalog(type, tag, page)
      .then((items) => {
        if (cancelled) return;
        setCatalog((previous) =>
          page === 0 ? items : [...previous, ...items],
        );
        setCatalogHasMore(items.length === 24);
      })
      .catch((error) => {
        if (!cancelled) setCatalogError(`豆瓣推荐暂时不可用：${error.message}`);
      })
      .finally(() => {
        if (!cancelled) setCatalogLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [type, tag, page, catalogReload]);

  async function searchFor(value: string) {
    const term = value.trim();
    if (!term) return;
    const serial = ++searchSerial.current;
    setQuery(term);
    setSearched(term);
    setSection("search");
    window.scrollTo(0, 0);
    setSelected(null);
    setSearchLoading(true);
    setSearchProgress(0);
    setResults([]);
    setFailed([]);
    const selectedSources = sources.filter(
      (source) => enabled.includes(source.url) && !source.isAdult,
    );
    if (selectedSources.length === 0) {
      setSearchLoading(false);
      return;
    }
    try {
      const data = await window.videoAPI.search(
        term,
        selectedSources,
        serial,
        (completed, total) => {
          if (serial === searchSerial.current)
            setSearchProgress(Math.round((completed / total) * 100));
        },
      );
      if (serial === searchSerial.current) {
        setResults(data.results);
        setFailed(data.failed);
      }
    } catch (error) {
      if (serial === searchSerial.current)
        setFailed([(error as Error).message]);
    } finally {
      if (serial === searchSerial.current) setSearchLoading(false);
    }
  }

  async function importSubscription(address: string) {
    if (testing || sourceBusy) return;
    setSourceBusy(true);
    setSourceError("");
    try {
      const list = await window.videoAPI.importSources(address);
      const nextEnabled =
        sources.length === 0
          ? list.filter((source) => !source.isAdult).map((source) => source.url)
          : enabled.filter((url) =>
              list.some((source) => source.url === url && !source.isAdult),
            );
      setSources(list);
      setEnabled(nextEnabled);
      setSubscriptionUrl(address.trim());
      setSubscriptionInput(address.trim());
      setSourceHealth({});
      setTested(0);
      setTestStopped(false);
      saveStorage("vod-sources", list);
      saveStorage("vod-enabled", nextEnabled);
      saveStorage("vod-subscription-url", address.trim());
    } catch (error) {
      setSourceError(`导入失败：${(error as Error).message}`);
    } finally {
      setSourceBusy(false);
    }
  }

  async function testAllSources() {
    const candidates = sources.filter((source) => !source.isAdult);
    if (candidates.length === 0 || testing) return;
    const runId = ++testSerial.current;
    setTesting(true);
    setTested(0);
    setTestStopped(false);
    setSourceHealth({});
    // 每批只探测少量站点，避免同时请求所有源造成网络拥塞。
    for (let index = 0; index < candidates.length; index += 6) {
      if (runId !== testSerial.current) break;
      await Promise.all(
        candidates.slice(index, index + 6).map(async (source) => {
          const result = await window.videoAPI
            .testSource(source, runId)
            .catch((error) => ({
              ok: false,
              latencyMs: 0,
              resultCount: 0,
              error: (error as Error).message,
            }));
          if (runId !== testSerial.current) return;
          setSourceHealth((previous) => ({
            ...previous,
            [source.url]: result,
          }));
          setTested((previous) => previous + 1);
          if (!result.ok) {
            setEnabled((previous) => {
              const next = previous.filter((url) => url !== source.url);
              saveStorage("vod-enabled", next);
              return next;
            });
          }
        }),
      );
    }
    if (runId === testSerial.current) setTesting(false);
  }

  function stopSourceTest() {
    if (!testing) return;
    window.videoAPI.cancelSourceTest(testSerial.current);
    ++testSerial.current;
    setTesting(false);
    setTestStopped(true);
  }

  async function openDetail(item: SearchItem, resumeItem?: HistoryItem) {
    setSelected(item);
    setDetail(null);
    setDetailError("");
    setDetailLoading(true);
    setCastOpen(false);
    setCasting("");
    setLine(resumeItem?.line || 0);
    setEpisode(resumeItem ? resumeItem.episode : null);
    try {
      setDetail(await window.videoAPI.detail(item.sourceUrl, item.id));
    } catch (error) {
      const message = (error as Error).message;
      setDetailError(
        /TimeoutError|aborted due to timeout|timed out/i.test(message)
          ? "点播源响应超时，请重试或返回列表选择其他来源。"
          : message,
      );
    } finally {
      setDetailLoading(false);
    }
  }

  function changeType(next: "movie" | "tv") {
    if (next === type && tag === "热门" && page === 0) {
      setSection("home");
      setSelected(null);
      return;
    }
    setType(next);
    setTag("热门");
    setPage(0);
    setCatalog([]);
    setCatalogLoading(true);
    setCatalogError("");
    setCatalogHasMore(true);
    setSection("home");
    setSelected(null);
  }
  function changeTag(next: string) {
    // 当前标签不会改变请求参数，重复清空列表会让页面一直保持空白。
    if (next === tag) return;
    setTag(next);
    setPage(0);
    setCatalog([]);
    setCatalogLoading(true);
    setCatalogError("");
    setCatalogHasMore(true);
  }

  function recordProgress(position: number, duration: number) {
    if (
      !selected ||
      episode === null ||
      Date.now() - progressSaved.current < 4000
    )
      return;
    progressSaved.current = Date.now();
    const item: HistoryItem = {
      key: `${selected.sourceUrl}:${selected.id}`,
      item: selected,
      line,
      episode,
      position,
      duration,
      updatedAt: Date.now(),
    };
    setHistory((previous) => {
      const next = [
        item,
        ...previous.filter((entry) => entry.key !== item.key),
      ].slice(0, 100);
      saveStorage("history", next);
      return next;
    });
  }

  function nextEpisode() {
    if (
      autoplay &&
      detail &&
      episode !== null &&
      episode + 1 < detail.lines[line].episodes.length
    )
      setEpisode(episode + 1);
  }

  async function scanDevices() {
    setCastOpen(true);
    setCastBusy(true);
    setCastError("");
    setCastWarnings([]);
    setPairingDevice(null);
    setDevices([]);
    try {
      const found = await window.videoAPI.devices();
      setDevices(found.items);
      setCastWarnings(found.warnings);
    } catch (error) {
      setCastError(`发现电视失败：${(error as Error).message}`);
    } finally {
      setCastBusy(false);
    }
  }

  async function castTo(device: Device) {
    const current = detail?.lines[line].episodes[episode || 0];
    if (!current || !detail) return;
    setCastBusy(true);
    setCastError("");
    try {
      if (device.type === "airplay" && !device.paired) {
        await window.videoAPI.airplayPairBegin(device.id);
        setPairingDevice(device);
        return;
      }
      const ok = await window.videoAPI.cast(
        device,
        current.url,
        `${detail.title} · ${current.label}`,
        filterAds,
      );
      if (!ok) throw new Error("电视未接受播放请求");
      setCasting(device.name);
      setCastOpen(false);
    } catch (error) {
      setCastError(`投屏失败：${(error as Error).message}`);
    } finally {
      setCastBusy(false);
    }
  }

  async function finishAirPlayPairing() {
    if (!pairingDevice || !/^\d{4,6}$/.test(airplayPin)) return;
    setCastBusy(true);
    setCastError("");
    try {
      await window.videoAPI.airplayPairFinish(airplayPin);
      const device = { ...pairingDevice, paired: true };
      setDevices((previous) =>
        previous.map((item) => (item.id === device.id ? device : item)),
      );
      setPairingDevice(null);
      setAirplayPin("");
      await castTo(device);
    } catch (error) {
      setCastError(`AirPlay 配对失败：${(error as Error).message}`);
      setPairingDevice(null);
      setAirplayPin("");
    } finally {
      setCastBusy(false);
    }
  }

  const featured = catalog[0];
  const testTotal = sources.filter((source) => !source.isAdult).length;
  const playing = detail?.lines[line]?.episodes[episode ?? 0];
  // 三列按钮约能放下五个汉字；更长的集名改为两列显示。
  const hasLongEpisodeLabels = detail?.lines[line]?.episodes.some(
    (item) => item.label.length > 5,
  );
  const selectedHistory = selected
    ? history.find(
        (item) => item.key === `${selected.sourceUrl}:${selected.id}`,
      )
    : undefined;

  return (
    <div className={`app-shell ${theme} platform-${window.videoAPI.platform}`}>
      <aside className="sidebar">
        <button
          className="brand"
          onClick={() => {
            setSection("home");
            setSelected(null);
          }}
        >
          <span className="brand-mark">
            <Play size={17} fill="currentColor" />
          </span>
          <span>
            影窗<strong>YINGCHUANG</strong>
          </span>
        </button>
        <div className="nav-caption">发现</div>
        <button
          className={`nav-item ${section === "home" && type === "movie" ? "active" : ""}`}
          onClick={() => changeType("movie")}
        >
          <Film size={19} /> 电影 <ArrowRight size={15} className="nav-arrow" />
        </button>
        <button
          className={`nav-item ${section === "home" && type === "tv" ? "active" : ""}`}
          onClick={() => changeType("tv")}
        >
          <Tv size={19} /> 电视剧 <ArrowRight size={15} className="nav-arrow" />
        </button>
        <div className="nav-caption library-caption">我的片单</div>
        <button
          className={`nav-item ${section === "history" ? "active" : ""}`}
          onClick={() => {
            setSection("history");
            setSelected(null);
          }}
        >
          <History size={19} /> 播放记录{" "}
          <span className="nav-count">{history.length}</span>
        </button>
        <button className="nav-item" onClick={() => setSettingsOpen(true)}>
          <Settings2 size={19} /> 播放设置
        </button>
        <div className="sidebar-bottom">
          <div className="source-status">
            <span className="status-dot" /> {enabled.length} 个点播源已启用
          </div>
          <p>发现值得看的每一部作品</p>
        </div>
      </aside>

      <div className="main-area">
        <header className="topbar">
          <span className="topbar-caption">DISCOVER YOUR NEXT FAVORITE</span>
          <form
            className="search-form"
            onSubmit={(event) => {
              event.preventDefault();
              searchFor(query);
            }}
          >
            <Search size={18} />
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索电影、剧集..."
              aria-label="搜索电影、剧集"
            />
            <button type="submit">搜索</button>
          </form>
          <div className="top-actions">
            <button
              title={theme === "dark" ? "切换浅色模式" : "切换深色模式"}
              onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
            >
              {theme === "dark" ? <Sun size={19} /> : <Moon size={19} />}
            </button>
            <button
              title="播放记录"
              onClick={() => {
                setSection("history");
                setSelected(null);
              }}
            >
              <Clock3 size={19} />
            </button>
            <button title="设置" onClick={() => setSettingsOpen(true)}>
              <Settings2 size={19} />
            </button>
          </div>
        </header>

        <main className="content">
          {section === "home" && (
            <>
              <section className="hero">
                <div className="hero-glow" />
                <div className="hero-copy">
                  <div className="eyebrow">
                    <Sparkles size={14} /> 豆瓣精选 · 每日发现
                  </div>
                  <h1>
                    光影之间，
                    <br />
                    <em>遇见好故事。</em>
                  </h1>
                  <p>电影与剧集，随心探索。想看的，点开就能找。</p>
                  <button
                    className="hero-button"
                    onClick={() => featured && searchFor(featured.title)}
                    disabled={!featured}
                  >
                    <Play size={16} fill="currentColor" /> 看看推荐{" "}
                    <ArrowRight size={17} />
                  </button>
                </div>
                {featured && <HeroPoster item={featured} />}
              </section>
              <section className="catalog-section">
                <div className="section-heading">
                  <div>
                    <span className="section-kicker">
                      EXPLORE THE COLLECTION
                    </span>
                    <h2>
                      {type === "movie" ? "电影" : "剧集"}推荐{" "}
                      <span>精选好内容</span>
                    </h2>
                  </div>
                  <div className="type-switch">
                    <button
                      className={type === "movie" ? "selected" : ""}
                      onClick={() => changeType("movie")}
                    >
                      电影
                    </button>
                    <button
                      className={type === "tv" ? "selected" : ""}
                      onClick={() => changeType("tv")}
                    >
                      剧集
                    </button>
                  </div>
                </div>
                <div className="tag-list">
                  {(type === "movie" ? MOVIE_TAGS : TV_TAGS).map((name) => (
                    <button
                      key={name}
                      className={tag === name ? "active" : ""}
                      onClick={() => changeTag(name)}
                    >
                      {name}
                    </button>
                  ))}
                </div>
                {catalogError && (
                  <div className="notice error">
                    {catalogError}{" "}
                    <button
                      onClick={() =>
                        setCatalogReload((previous) => previous + 1)
                      }
                    >
                      重试
                    </button>
                  </div>
                )}
                <div className="poster-grid">
                  {catalog.map((item, index) => (
                    <button
                      className="poster-card"
                      key={`${item.id}-${index}`}
                      onClick={() => searchFor(item.title)}
                    >
                      <div className="poster-image">
                        <PosterImage
                          src={item.poster}
                          alt={`${item.title}海报`}
                        />
                        <span className="poster-play">
                          <Play size={24} fill="currentColor" />
                        </span>
                        {item.rating && (
                          <span className="rating">★ {item.rating}</span>
                        )}
                      </div>
                      <div className="poster-info">
                        <strong title={item.title}>{item.title}</strong>
                        {item.episodesInfo && (
                          <small>{item.episodesInfo}</small>
                        )}
                      </div>
                    </button>
                  ))}
                </div>
                {catalogLoading && (
                  <div className="loading">
                    <LoaderCircle className="spin" size={20} /> 正在加载推荐...
                  </div>
                )}
                {!catalogLoading && !catalogError && catalog.length === 0 && (
                  <div className="empty">
                    <Film size={36} />
                    <h3>这个分类暂时没有推荐</h3>
                    <p>可以试试其他分类，或稍后再来看。</p>
                  </div>
                )}
                {!catalogLoading &&
                  !catalogError &&
                  catalogHasMore &&
                  catalog.length > 0 && (
                    <button
                      className="load-more"
                      onClick={() => setPage(page + 1)}
                    >
                      加载更多 <ChevronDown size={16} />
                    </button>
                  )}
              </section>
            </>
          )}

          {section === "search" && (
            <section className="results-section">
              <div className="section-heading">
                <div>
                  <span className="section-kicker">SEARCH RESULTS</span>
                  <h2>
                    “{searched}” 的搜索结果{" "}
                    <span>
                      {searchLoading ? "搜索中..." : `共 ${results.length} 条`}
                    </span>
                  </h2>
                </div>
              </div>
              <p className="search-subtitle">
                来自 {enabled.length} 个已启用点播源 · 点击资源查看线路与选集
              </p>
              {failed.length > 0 && (
                <div className="notice">
                  {failed.length} 个源暂时无响应：
                  {failed.slice(0, 6).join("、")}
                  {failed.length > 6 ? "…" : ""}
                </div>
              )}
              {searchLoading && (
                <div className="search-wait" role="status" aria-live="polite">
                  <div className="search-wait-card">
                    <div className="search-wait-symbol" aria-hidden="true">
                      <Search size={24} />
                    </div>
                    <p>正在搜索各个点播源，请稍候...</p>
                    <div className="search-progress-label">
                      <span>搜索进度</span>
                      <b>{searchProgress}%</b>
                    </div>
                    <div
                      className="search-progress"
                      role="progressbar"
                      aria-label="搜索进度"
                      aria-valuenow={searchProgress}
                      aria-valuemin={0}
                      aria-valuemax={100}
                    >
                      <span style={{ width: `${searchProgress}%` }} />
                    </div>
                    <small>搜索结果即将呈现</small>
                  </div>
                </div>
              )}
              {!searchLoading && enabled.length === 0 && (
                <div className="empty">
                  <Settings2 size={36} />
                  <h3>先导入点播源</h3>
                  <p>在播放设置中粘贴 VOD 源订阅地址，导入后即可搜索。</p>
                  <button
                    className="primary-button"
                    onClick={() => setSettingsOpen(true)}
                  >
                    导入点播源
                  </button>
                </div>
              )}
              {!searchLoading && enabled.length > 0 && results.length === 0 && (
                <div className="empty">
                  <Search size={36} />
                  <h3>没有找到相关资源</h3>
                  <p>试试更短的片名，或在设置中启用更多点播源。</p>
                </div>
              )}
              <div className="poster-grid result-grid">
                {results.map((item) => (
                  <button
                    className="poster-card"
                    key={`${item.sourceUrl}:${item.id}`}
                    onClick={() => openDetail(item)}
                  >
                    <div className="poster-image">
                      <PosterImage
                        src={item.poster}
                        alt={`${item.title}海报`}
                      />
                      <span className="poster-play">
                        <Play size={24} fill="currentColor" />
                      </span>
                      {item.rating && item.rating !== "0.0" && (
                        <span className="rating">★ {item.rating}</span>
                      )}
                    </div>
                    <div className="poster-info">
                      <strong title={item.title}>{item.title}</strong>
                      <div className="result-meta">
                        <span>{item.source}</span>
                        <small>{item.year || item.remarks}</small>
                      </div>
                    </div>
                  </button>
                ))}
              </div>
            </section>
          )}

          {section === "history" && (
            <section className="results-section">
              <div className="section-heading">
                <div>
                  <span className="section-kicker">
                    PICK UP WHERE YOU LEFT OFF
                  </span>
                  <h2>
                    播放记录 <span>继续你的观影时光</span>
                  </h2>
                </div>
                {history.length > 0 && (
                  <button
                    className="subtle-button"
                    onClick={() => {
                      setHistory([]);
                      saveStorage("history", []);
                    }}
                  >
                    清空记录
                  </button>
                )}
              </div>
              {history.length === 0 ? (
                <div className="empty">
                  <History size={38} />
                  <h3>还没有播放记录</h3>
                  <p>找到喜欢的影片并开始播放，进度会自动保存在这里。</p>
                </div>
              ) : (
                <div className="history-list">
                  {history.map((entry) => (
                    <button
                      key={entry.key}
                      className="history-card"
                      onClick={() => openDetail(entry.item, entry)}
                    >
                      <PosterImage src={entry.item.poster} />
                      <div>
                        <strong>{entry.item.title}</strong>
                        <p>
                          {entry.item.source} · 第 {entry.episode + 1} 集
                        </p>
                        <div className="progress">
                          <span
                            style={{
                              width: `${Math.min(100, entry.duration ? (entry.position / entry.duration) * 100 : 0)}%`,
                            }}
                          />
                        </div>
                        <small>
                          已播放 {Math.floor(entry.position / 60)} 分钟
                        </small>
                      </div>
                      <Play size={18} />
                    </button>
                  ))}
                </div>
              )}
            </section>
          )}
        </main>
      </div>

      {selected && (
        <div className="detail-overlay">
          <div className="detail-toolbar">
            <button
              onClick={() => {
                setSelected(null);
                setDetail(null);
                setEpisode(null);
              }}
            >
              <ArrowLeft size={19} /> 返回列表
            </button>
            <span>影片详情</span>
            <button
              className="icon-button"
              title="关闭"
              onClick={() => {
                setSelected(null);
                setDetail(null);
                setEpisode(null);
              }}
            >
              <X size={20} />
            </button>
          </div>
          {detailLoading ? (
            <div className="loading detail-loading">
              <LoaderCircle className="spin" size={24} /> 正在获取播放线路...
            </div>
          ) : detailError ? (
            <div className="empty">
              <h3>获取详情失败</h3>
              <p>{detailError}</p>
              <button
                className="primary-button"
                onClick={() => openDetail(selected)}
              >
                重试
              </button>
            </div>
          ) : (
            detail && (
              <div className="detail-content">
                <div className="detail-main">
                  {episode === null ? (
                    <div className="detail-hero">
                      <PosterImage src={detail.poster || selected.poster} />
                      <div className="detail-hero-copy">
                        <div className="eyebrow">
                          <Clapperboard size={15} /> {selected.source} ·{" "}
                          {selected.type || "影视"}
                        </div>
                        <h1>{detail.title}</h1>
                        <div className="detail-facts">
                          {detail.year && <span>{detail.year}</span>}
                          {detail.area && <span>{detail.area}</span>}
                          {selected.rating && <span>★ {selected.rating}</span>}
                        </div>
                        <p>{detail.description || "暂无剧情介绍"}</p>
                        {detail.actors && <small>主演：{detail.actors}</small>}
                        <button
                          className="primary-button"
                          onClick={() => setEpisode(0)}
                          disabled={!playing}
                        >
                          <Play size={17} fill="currentColor" />{" "}
                          {selectedHistory ? "继续播放" : "立即播放"}
                        </button>
                      </div>
                    </div>
                  ) : (
                    playing && (
                      <>
                        <Player
                          key={`${playing.url}-${filterAds}`}
                          url={playing.url}
                          filterAds={filterAds}
                          resume={
                            selectedHistory?.line === line &&
                            selectedHistory.episode === episode
                              ? selectedHistory.position
                              : 0
                          }
                          onProgress={recordProgress}
                          onEnded={nextEpisode}
                        />
                        <div className="player-title">
                          <div>
                            <span>正在播放</span>
                            <h2>
                              {detail.title} <small>· {playing.label}</small>
                            </h2>
                          </div>
                          <div className="player-actions">
                            <button onClick={scanDevices}>
                              <MonitorPlay size={17} />{" "}
                              {casting ? `已投至 ${casting}` : "投屏"}
                            </button>
                            {episode + 1 <
                              detail.lines[line].episodes.length && (
                              <button onClick={() => setEpisode(episode + 1)}>
                                <SkipForward size={17} /> 下一集
                              </button>
                            )}
                          </div>
                        </div>
                        {casting && (
                          <div className="notice casting-notice">
                            <Wifi size={17} /> 正在电视「{casting}」播放{" "}
                            <button
                              onClick={() => {
                                window.videoAPI.castStop().catch(() => {});
                                setCasting("");
                              }}
                            >
                              停止投屏
                            </button>
                          </div>
                        )}
                        <section className="player-details">
                          <div className="player-details-cover">
                            <PosterImage
                              src={detail.poster || selected.poster}
                              alt={`${detail.title}海报`}
                            />
                          </div>
                          <div className="player-details-copy">
                            <span className="player-details-kicker">
                              影片详情
                            </span>
                            <h3>{detail.title}</h3>
                            <div className="player-details-meta">
                              {detail.year && <span>{detail.year}</span>}
                              {detail.area && <span>{detail.area}</span>}
                              {selected.type && <span>{selected.type}</span>}
                              <span>来源：{selected.source}</span>
                            </div>
                            {detail.actors && (
                              <p className="player-details-actors">
                                主演：{detail.actors}
                              </p>
                            )}
                            <p className="player-details-description">
                              {detail.description || "暂无剧情介绍"}
                            </p>
                          </div>
                        </section>
                      </>
                    )
                  )}
                </div>
                <div className="episode-panel">
                  <div className="panel-title">
                    <span>播放线路</span>
                    <strong>{detail.lines.length} 条</strong>
                  </div>
                  {detail.lines.length === 0 ? (
                    <div className="notice">
                      这个资源暂时没有可播放地址，请返回换一个源。
                    </div>
                  ) : (
                    <>
                      <div className="line-list">
                        {detail.lines.map((item, index) => (
                          <button
                            key={`${item.name}-${index}`}
                            className={line === index ? "active" : ""}
                            onClick={() => {
                              setLine(index);
                              setEpisode(null);
                            }}
                          >
                            {item.name}
                          </button>
                        ))}
                      </div>
                      <div className="panel-title episodes-title">
                        <span>选集播放</span>
                        <strong>
                          共 {detail.lines[line].episodes.length} 集
                        </strong>
                      </div>
                      <div
                        className={
                          hasLongEpisodeLabels
                            ? "episode-list episode-list-long"
                            : "episode-list"
                        }
                      >
                        {detail.lines[line].episodes.map((item, index) => (
                          <button
                            key={`${item.url}-${index}`}
                            className={episode === index ? "active" : ""}
                            onClick={() => setEpisode(index)}
                            title={item.label}
                          >
                            {item.label}
                          </button>
                        ))}
                      </div>
                      <div className="panel-tip">
                        <ShieldCheck size={16} /> 广告过滤
                        {filterAds ? "已开启" : "已关闭"} · 自动连播
                        {autoplay ? "已开启" : "已关闭"}
                      </div>
                    </>
                  )}
                </div>
              </div>
            )
          )}
        </div>
      )}

      {settingsOpen && (
        <div
          className="drawer-backdrop"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setSettingsOpen(false);
          }}
        >
          <div className="settings-drawer">
            <div className="drawer-header">
              <div>
                <span>偏好设置</span>
                <h2>让观影更顺手</h2>
              </div>
              <button
                className="icon-button"
                onClick={() => setSettingsOpen(false)}
              >
                <X size={20} />
              </button>
            </div>
            <div className="settings-body">
              <h3>外观</h3>
              <div className="theme-switch" role="group" aria-label="颜色模式">
                <button
                  className={theme === "light" ? "active" : ""}
                  onClick={() => setTheme("light")}
                >
                  <Sun size={16} /> 浅色模式
                </button>
                <button
                  className={theme === "dark" ? "active" : ""}
                  onClick={() => setTheme("dark")}
                >
                  <Moon size={16} /> 深色模式
                </button>
              </div>
              <h3>播放</h3>
              <label className="setting-row">
                <div>
                  <strong>广告过滤</strong>
                  <p>过滤清单中标记的广告片段</p>
                </div>
                <input
                  type="checkbox"
                  checked={filterAds}
                  onChange={(event) => {
                    setFilterAds(event.target.checked);
                    saveStorage("filter-ads", event.target.checked);
                  }}
                />
              </label>
              <label className="setting-row">
                <div>
                  <strong>自动连播</strong>
                  <p>当前一集结束后播放下一集</p>
                </div>
                <input
                  type="checkbox"
                  checked={autoplay}
                  onChange={(event) => {
                    setAutoplay(event.target.checked);
                    saveStorage("autoplay", event.target.checked);
                  }}
                />
              </label>
              <div className="settings-subhead">
                <h3>
                  点播源{" "}
                  <small>
                    {enabled.length} / {testTotal} 已启用
                  </small>
                </h3>
              </div>
              <p className="settings-note">
                输入订阅地址导入点播源；成人源不参与搜索。
              </p>
              <form
                className="source-import"
                onSubmit={(event) => {
                  event.preventDefault();
                  importSubscription(subscriptionInput);
                }}
              >
                <input
                  type="url"
                  value={subscriptionInput}
                  onChange={(event) => setSubscriptionInput(event.target.value)}
                  placeholder="粘贴 vod.json 订阅地址"
                  aria-label="点播源订阅地址"
                  required
                />
                <button type="submit" disabled={sourceBusy || testing}>
                  {sourceBusy ? (
                    <LoaderCircle className="spin" size={15} />
                  ) : (
                    <ArrowRight size={15} />
                  )}{" "}
                  导入源
                </button>
              </form>
              {subscriptionUrl && (
                <p className="current-subscription" title={subscriptionUrl}>
                  当前订阅：{subscriptionUrl}
                </p>
              )}
              {sourceError && <div className="notice error">{sourceError}</div>}
              {sources.length > 0 && (
                <div className="source-actions">
                  <button
                    onClick={() => importSubscription(subscriptionUrl)}
                    disabled={sourceBusy || testing || !subscriptionUrl}
                  >
                    <RefreshCw size={15} /> 一键更新
                  </button>
                  <button
                    onClick={() => {
                      const next = sources
                        .filter((source) => !source.isAdult)
                        .map((source) => source.url);
                      setEnabled(next);
                      saveStorage("vod-enabled", next);
                    }}
                    disabled={testing}
                  >
                    <Check size={15} /> 全部启用
                  </button>
                  {testing ? (
                    <button className="stop-test" onClick={stopSourceTest}>
                      <Square size={14} fill="currentColor" /> 停止测活
                    </button>
                  ) : (
                    <button onClick={testAllSources} disabled={sourceBusy}>
                      <ShieldCheck size={15} /> 一键测试
                    </button>
                  )}
                </div>
              )}
              {(testing || tested > 0 || testStopped) && testTotal > 0 && (
                <div className="source-test-progress">
                  <div className="source-test-progress-label">
                    <strong>
                      {testing
                        ? "正在测活"
                        : testStopped
                          ? "测活已停止"
                          : "测活完成"}
                    </strong>
                    <span>
                      {tested} / {testTotal} ·{" "}
                      {Math.round((tested / testTotal) * 100)}%
                    </span>
                  </div>
                  <div
                    className="source-test-track"
                    role="progressbar"
                    aria-label="点播源测活进度"
                    aria-valuemin={0}
                    aria-valuemax={testTotal}
                    aria-valuenow={tested}
                  >
                    <span style={{ width: `${(tested / testTotal) * 100}%` }} />
                  </div>
                  <p>
                    {testStopped
                      ? "已保留完成的结果，可重新测活。"
                      : "每批并发测试 6 个源，完成后自动更新勾选状态。"}
                  </p>
                </div>
              )}
              {sources.length === 0 && (
                <div className="source-empty">
                  尚未导入点播源。首页推荐仍可浏览，导入后可搜索和播放。
                </div>
              )}
              <div className="source-list">
                {sources
                  .filter((source) => !source.isAdult)
                  .map((source) => (
                    <label key={source.url} className="source-row">
                      <input
                        type="checkbox"
                        checked={enabled.includes(source.url)}
                        onChange={(event) => {
                          const next = event.target.checked
                            ? [...enabled, source.url]
                            : enabled.filter((url) => url !== source.url);
                          setEnabled(next);
                          saveStorage("vod-enabled", next);
                        }}
                      />
                      <span className="source-row-copy">
                        <strong>{source.name}</strong>
                        <small>{new URL(source.url).host}</small>
                      </span>
                      {sourceHealth[source.url] && (
                        <span
                          className={`health-badge ${sourceHealth[source.url].ok ? "ok" : "bad"}`}
                          title={sourceHealth[source.url].error || ""}
                        >
                          {sourceHealth[source.url].ok
                            ? `搜索可用 · ${sourceHealth[source.url].latencyMs}ms · ${sourceHealth[source.url].resultCount}条`
                            : `搜索失败 · ${sourceHealth[source.url].latencyMs}ms · ${sourceHealth[source.url].error || "未知错误"}`}
                        </span>
                      )}
                    </label>
                  ))}
              </div>
              <a
                className="source-link"
                href="https://github.com/LibreSpark/LibreTV/wiki/Data-Sources"
                target="_blank"
                rel="noreferrer"
              >
                了解点播源格式 <ExternalLink size={14} />
              </a>
            </div>
          </div>
        </div>
      )}

      {castOpen && (
        <div
          className="modal-backdrop"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) setCastOpen(false);
          }}
        >
          <div className="cast-modal">
            <div className="modal-heading">
              <div>
                <MonitorPlay size={22} />
                <h2>投屏到电视</h2>
              </div>
              <button
                className="icon-button"
                onClick={() => setCastOpen(false)}
              >
                <X size={20} />
              </button>
            </div>
            <p>
              确保电脑和电视在同一局域网。支持 DLNA 和 AirPlay 视频接收设备。
            </p>
            {castBusy && (
              <div className="loading">
                <LoaderCircle size={18} className="spin" />{" "}
                正在搜索或连接电视...
              </div>
            )}
            {castError && <div className="notice error">{castError}</div>}
            {castWarnings.map((warning) => (
              <div className="notice error" key={warning}>
                {warning}
              </div>
            ))}
            {pairingDevice && (
              <form
                className="airplay-pairing"
                onSubmit={(event) => {
                  event.preventDefault();
                  finishAirPlayPairing();
                }}
              >
                <label htmlFor="airplay-pin">
                  输入电视「{pairingDevice.name}」显示的 AirPlay 验证码
                </label>
                <div>
                  <input
                    id="airplay-pin"
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    value={airplayPin}
                    onChange={(event) =>
                      setAirplayPin(
                        event.target.value.replace(/\D/g, "").slice(0, 6),
                      )
                    }
                    placeholder="4 至 6 位验证码"
                    autoFocus
                  />
                  <button
                    type="submit"
                    disabled={castBusy || !/^\d{4,6}$/.test(airplayPin)}
                  >
                    连接
                  </button>
                </div>
              </form>
            )}
            {!castBusy && devices.length === 0 && (
              <div className="empty small">
                <Tv size={30} />
                <h3>未发现设备</h3>
                <p>检查电视的投屏功能和网络连接。</p>
              </div>
            )}
            {devices.map((device) => (
              <button
                key={`${device.type}:${device.id}`}
                className="device-row"
                onClick={() => castTo(device)}
                disabled={castBusy}
              >
                <Tv size={20} />
                <span>
                  {device.name}
                  <small>
                    {device.type === "airplay" ? "AirPlay" : "DLNA"} ·{" "}
                    {device.host}
                    {!device.paired ? " · 需配对" : ""}
                  </small>
                </span>
                <ArrowRight size={17} />
              </button>
            ))}
            <button className="load-more" onClick={scanDevices}>
              <RefreshCw size={15} /> 重新搜索
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
