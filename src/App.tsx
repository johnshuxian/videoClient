import { useEffect, useRef, useState } from "react";
import { version } from "../package.json";
import type Artplayer from "artplayer";
import type Hls from "hls.js";
import { App as CapacitorApp } from "@capacitor/app";
import {
  ArrowLeft,
  ArrowRight,
  Check,
  ChevronDown,
  Clock3,
  Clapperboard,
  Film,
  History,
  ListFilter,
  LoaderCircle,
  MonitorPlay,
  Maximize2,
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
  Minus,
  Tv,
  X,
} from "lucide-react";
import type {
  CatalogItem,
  CatalogType,
  Device,
  HistoryItem,
  SearchItem,
  Source,
  SourceCategory,
  SourceHealth,
  VodDetail,
} from "./types";
import { focusTvElement, moveTvFocus } from "./tv-navigation";

// 补齐豆瓣分类接口列出的标签，并保留现有可用的“动画”和“最新”入口。
const MOVIE_TAGS = [
  "热门",
  "最新",
  "经典",
  "可播放",
  "豆瓣高分",
  "冷门佳片",
  "华语",
  "欧美",
  "韩国",
  "日本",
  "动作",
  "喜剧",
  "爱情",
  "科幻",
  "悬疑",
  "恐怖",
  "成长",
  "动画",
];
const TV_TAGS = [
  "热门",
  "最新",
  "国产剧",
  "港剧",
  "美剧",
  "英剧",
  "日剧",
  "韩剧",
  "日本动画",
  "综艺",
  "纪录片",
];
const ANIME_FORMAT_TAGS = ["TV", "OVA", "剧场版", "WEB"];
const ANIME_GENRE_TAGS = [
  "科幻",
  "治愈",
  "恋爱",
  "战斗",
  "校园",
  "悬疑",
  "奇幻",
  "日常",
];
const ANIME_FORMAT_SORTS = [
  { value: "rank", label: "高分" },
  { value: "date", label: "日期" },
] as const;
const ANIME_GENRE_SORTS = [
  { value: "rank", label: "高分" },
  { value: "heat", label: "热度" },
] as const;
function readStorage<T>(key: string, fallback: T): T {
  const saved = window.videoAPI.readSetting(key);
  return saved === undefined ? fallback : (saved as T);
}

function saveStorage(key: string, value: unknown) {
  window.videoAPI.saveSetting(key, value);
}

function useProxyImage(original: string) {
  const [url, setUrl] = useState("");
  useEffect(() => {
    let active = true;
    setUrl("");
    if (original) {
      window.videoAPI
        .imageUrl(original)
        .then((proxied) => {
          if (active) setUrl(proxied);
        })
        .catch(() => {
          if (active) setUrl(original);
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
  const [failure, setFailure] = useState({ url: "", attempt: 0, retryAt: 0 });
  const retryTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => {
    return () => {
      if (retryTimer.current) clearTimeout(retryTimer.current);
    };
  }, [url]);
  const attempt = failure.url === url ? failure.attempt : 0;
  // 代理首次失败时换一个地址重试，绕开 ArkWeb 对失败请求的缓存。
  const image =
    attempt === 1
      ? `${url}${url.includes("?") ? "&" : "?"}retry=${failure.retryAt}`
      : attempt === 2
        ? src
        : url;
  return url && attempt < 3 ? (
    <img
      src={image}
      alt={alt}
      loading={loading}
      onLoad={(event) => {
        event.currentTarget.style.visibility = "visible";
      }}
      onError={(event) => {
        // 重试期间保留卡片背景占位，避免显示浏览器的损坏图片图标。
        event.currentTarget.style.visibility = "hidden";
        if (url === src || attempt === 2) {
          setFailure({ url, attempt: 3, retryAt: 0 });
        } else if (attempt === 0) {
          retryTimer.current = setTimeout(() => {
            setFailure({ url, attempt: 1, retryAt: Date.now() });
          }, 180);
        } else {
          // 代理重试仍失败时，再尝试 WebView 直连原图。
          setFailure({ url, attempt: 2, retryAt: 0 });
        }
      }}
    />
  ) : null;
}

function HeroPoster({
  item,
  ratingSource,
}: {
  item: CatalogItem;
  ratingSource: string;
}) {
  return (
    <div className="hero-poster">
      <PosterImage src={item.poster} loading="eager" />
      <div className="hero-poster-label">
        <span>今日推荐</span>
        <strong>{item.title}</strong>
        {item.rating && (
          <small>
            ★ {item.rating} {ratingSource}评分
          </small>
        )}
      </div>
    </div>
  );
}

function enableMobilePlaybackGestures(art: Artplayer) {
  const player = art.template.$player;
  const hint = document.createElement("div");
  hint.className = "mobile-seek-hint";
  player.append(hint);
  let pointerId: number | null = null;
  let pressX = 0;
  let pressY = 0;
  let gestureRotated = false;
  let direction: -1 | 1 = 1;
  let holdTimer: number | undefined;
  let previewTimer: number | undefined;
  let clickTimer: number | undefined;
  let seekStartedAt = 0;
  let seekStartPosition = 0;
  let previewPosition = 0;
  let originalPlaybackRate = 1;
  let wasPaused = false;
  let holdActive = false;
  let adjustMode: "brightness" | "volume" | null = null;
  let adjustStart = 0;
  let adjustValue = 0;
  let brightness = 0.5;
  let lastBrightnessSent = 0;
  let suppressClick = false;

  const readBrightness = () => {
    void window.videoAPI.getVideoBrightness?.().then((value) => {
      if (!adjustMode) brightness = value;
    }).catch(() => {});
  };
  const onFullscreenWeb = (fullscreen: boolean) => {
    if (fullscreen) readBrightness();
    else {
      adjustMode = null;
      stopGesture(false);
      window.videoAPI.resetVideoBrightness?.();
    }
  };
  art.on("fullscreenWeb", onFullscreenWeb);

  const updateRewindPreview = () => {
    // 只更新回退目标，松手时才 seek 一次，避免 HLS 反复拉流和解码。
    const elapsed = (performance.now() - seekStartedAt) / 1000;
    previewPosition = Math.max(0, seekStartPosition - elapsed * 2);
    const minutes = Math.floor(previewPosition / 60);
    const seconds = String(Math.floor(previewPosition % 60)).padStart(2, "0");
    hint.textContent = `快退 2× · 松手跳转 ${minutes}:${seconds}`;
  };
  const stopGesture = (restorePlayback = true) => {
    window.clearTimeout(holdTimer);
    window.clearInterval(previewTimer);
    if (adjustMode === "brightness")
      window.videoAPI.setVideoBrightness?.(adjustValue);
    else if (adjustMode === "volume") art.volume = adjustValue;
    if (holdActive) {
      if (direction > 0) {
        art.video.playbackRate = originalPlaybackRate;
        if (wasPaused) art.video.pause();
      } else if (restorePlayback) {
        updateRewindPreview();
        art.video.currentTime = previewPosition;
        if (!wasPaused) void art.video.play().catch(() => {});
      }
    }
    hint.classList.remove("active", "adjust");
    holdTimer = undefined;
    previewTimer = undefined;
    holdActive = false;
    adjustMode = null;
    pointerId = null;
  };
  const onPointerDown = (event: PointerEvent) => {
    if (event.pointerType !== "touch" || !event.isPrimary || pointerId !== null)
      return;
    const target = event.target;
    if (
      !(target instanceof Node) ||
      [
        art.template.$bottom,
        art.template.$setting,
        art.template.$contextmenu,
        art.template.$infoPanel,
      ].some((control) => control.contains(target))
    )
      return;
    const duration = art.video.duration;
    const canSeek = Number.isFinite(duration) && duration > 0.1;
    if (!canSeek && !art.fullscreenWeb && !art.fullscreen) return;
    const rect = player.getBoundingClientRect();
    gestureRotated = art.isRotate;
    // 播放器单独旋转后，屏幕纵轴才是播放器的左右方向。
    const fraction = gestureRotated
      ? (event.clientY - rect.top) / rect.height
      : (event.clientX - rect.left) / rect.width;
    // 中间保留给播放器原有的播放、暂停点击区域。
    if (fraction >= 0.4 && fraction <= 0.6) return;
    pointerId = event.pointerId;
    pressX = event.clientX;
    pressY = event.clientY;
    direction = fraction < 0.4 ? -1 : 1;
    if (canSeek) holdTimer = window.setTimeout(() => {
      if (pointerId !== event.pointerId) return;
      holdActive = true;
      wasPaused = art.video.paused;
      originalPlaybackRate = art.video.playbackRate;
      seekStartPosition = art.video.currentTime;
      seekStartedAt = performance.now();
      if (direction > 0) {
        // 正向直接倍速播放，视频和声音保持连续。
        art.video.playbackRate = 2;
        hint.textContent = "快进 2×";
        if (wasPaused)
          void art.video
            .play()
            .then(() => {
              if (!holdActive) art.video.pause();
            })
            .catch(() => {});
      } else {
        art.video.pause();
        updateRewindPreview();
        previewTimer = window.setInterval(updateRewindPreview, 100);
      }
      hint.classList.toggle("rewind", direction < 0);
      hint.classList.add("active");
    }, 400);
  };
  const onPointerMove = (event: PointerEvent) => {
    if (event.pointerId !== pointerId || holdActive) return;
    const deltaX = gestureRotated ? event.clientY - pressY : event.clientX - pressX;
    const deltaY = gestureRotated ? pressX - event.clientX : event.clientY - pressY;
    if (
      !adjustMode &&
      (art.fullscreenWeb || art.fullscreen) &&
      Math.abs(deltaY) > 18 &&
      Math.abs(deltaY) > Math.abs(deltaX) * 1.2
    ) {
      window.clearTimeout(holdTimer);
      adjustMode = direction < 0 ? "brightness" : "volume";
      adjustStart = adjustMode === "brightness" ? brightness : art.volume;
      hint.classList.remove("rewind");
      hint.classList.add("adjust", "active");
    }
    if (adjustMode) {
      const rect = player.getBoundingClientRect();
      const height = gestureRotated ? rect.width : rect.height;
      adjustValue = Math.max(
        adjustMode === "brightness" ? 0.05 : 0,
        Math.min(1, adjustStart - (deltaY / height) * 1.5),
      );
      if (adjustMode === "brightness") {
        brightness = adjustValue;
        if (performance.now() - lastBrightnessSent > 80) {
          window.videoAPI.setVideoBrightness?.(adjustValue);
          lastBrightnessSent = performance.now();
        }
      } else {
        art.muted = false;
        art.video.volume = adjustValue;
      }
      hint.textContent = `${adjustMode === "brightness" ? "亮度" : "音量"} ${Math.round(adjustValue * 100)}%`;
      event.preventDefault();
      event.stopPropagation();
      return;
    }
    if (Math.hypot(deltaX, deltaY) > 24)
      stopGesture(false);
  };
  const onPointerEnd = (event: PointerEvent) => {
    if (event.pointerId !== pointerId) return;
    if (holdActive || adjustMode) {
      // 长按结束后的合成点击不能再触发 Artplayer 的播放/暂停。
      event.preventDefault();
      event.stopPropagation();
      suppressClick = true;
      window.clearTimeout(clickTimer);
      clickTimer = window.setTimeout(() => (suppressClick = false), 500);
    }
    stopGesture();
  };
  const onTouchEnd = (event: TouchEvent) => {
    if (
      (!holdActive && !adjustMode && !suppressClick) ||
      !player.contains(event.target as Node)
    )
      return;
    event.preventDefault();
    event.stopPropagation();
  };
  const onClick = (event: MouseEvent) => {
    if (!suppressClick || !player.contains(event.target as Node)) return;
    event.preventDefault();
    event.stopPropagation();
    suppressClick = false;
  };
  const onContextMenu = (event: MouseEvent) => {
    if (pointerId === null) return;
    event.preventDefault();
    event.stopPropagation();
  };
  const onVisibilityChange = () => {
    if (document.hidden) stopGesture(false);
  };
  const listeners = new AbortController();
  const capture = { capture: true, signal: listeners.signal };
  player.addEventListener("pointerdown", onPointerDown, capture);
  player.addEventListener("contextmenu", onContextMenu, capture);
  document.addEventListener("pointermove", onPointerMove, capture);
  document.addEventListener("pointerup", onPointerEnd, capture);
  document.addEventListener("pointercancel", onPointerEnd, capture);
  document.addEventListener("touchend", onTouchEnd, capture);
  document.addEventListener("click", onClick, capture);
  document.addEventListener("visibilitychange", onVisibilityChange, capture);
  return () => {
    adjustMode = null;
    stopGesture(false);
    art.off("fullscreenWeb", onFullscreenWeb);
    window.videoAPI.resetVideoBrightness?.();
    window.clearTimeout(clickTimer);
    listeners.abort();
    hint.remove();
  };
}

function Player({
  url,
  filterAds,
  playbackSerial,
  resume,
  title,
  episodeLabel,
  hasNextEpisode,
  onProgress,
  onEnded,
  onNextEpisode,
  onFailure,
  playerInstance,
  tvFullscreen,
}: {
  url: string;
  filterAds: boolean;
  playbackSerial: number;
  resume: number;
  title: string;
  episodeLabel: string;
  hasNextEpisode: boolean;
  onProgress: (position: number, duration: number) => void;
  onEnded: () => void;
  onNextEpisode: () => void;
  onFailure: (position: number) => boolean;
  playerInstance: React.RefObject<Artplayer | null>;
  tvFullscreen: boolean;
}) {
  const playerRef = useRef<HTMLDivElement>(null);
  const artRef = useRef<Artplayer | null>(null);
  const hlsRef = useRef<Hls | null>(null);
  const hlsTypeRef = useRef<typeof Hls | null>(null);
  const stopTouchGesturesRef = useRef<(() => void) | null>(null);
  const fullscreenTitleRef = useRef<HTMLDivElement | null>(null);
  const titleTextRef = useRef(`${title} · ${episodeLabel}`);
  titleTextRef.current = `${title} · ${episodeLabel}`;
  const hasNextRef = useRef(hasNextEpisode);
  hasNextRef.current = hasNextEpisode;
  const onNextRef = useRef(onNextEpisode);
  onNextRef.current = onNextEpisode;
  const sourceRef = useRef<{
    fail: (message: string) => void;
    clearTimers: () => void;
    watchStall: () => void;
    resume: number;
    loaded: boolean;
    shouldPlay: boolean;
  } | null>(null);
  const onFailureRef = useRef(onFailure);
  onFailureRef.current = onFailure;
  const onProgressRef = useRef(onProgress);
  onProgressRef.current = onProgress;
  const onEndedRef = useRef(onEnded);
  onEndedRef.current = onEnded;
  const [error, setError] = useState("");

  useEffect(() => {
    if (fullscreenTitleRef.current)
      fullscreenTitleRef.current.textContent = titleTextRef.current;
    artRef.current?.template.$player.classList.toggle(
      "has-next-episode",
      hasNextEpisode,
    );
  }, [title, episodeLabel, hasNextEpisode]);

  useEffect(() => {
    return () => {
      sourceRef.current?.clearTimers();
      stopTouchGesturesRef.current?.();
      hlsRef.current?.destroy();
      const art = artRef.current;
      // 网页全屏会把播放器节点移到 body，销毁前先放回原容器。
      if (art?.fullscreenWeb) art.fullscreenWeb = false;
      if (playerInstance.current === art) playerInstance.current = null;
      art?.destroy(false);
      artRef.current = null;
      fullscreenTitleRef.current = null;
    };
  }, [playerInstance]);

  useEffect(() => {
    let cancelled = false;
    let failed = false;
    let startupTimeout: number | undefined;
    let stallTimeout: number | undefined;
    setError("");
    // 切集只更换视频源，保留 ArtPlayer 的 DOM 与全屏状态。
    artRef.current?.video.pause();
    const clearTimers = () => {
      window.clearTimeout(startupTimeout);
      window.clearTimeout(stallTimeout);
      stallTimeout = undefined;
    };
    const failPlayback = (message: string) => {
      if (cancelled || failed) return;
      failed = true;
      clearTimers();
      // 新线路尚未加载时，旧视频的结束时间不能当成新线路的续播位置。
      const position = sourceRef.current?.loaded
        ? artRef.current?.currentTime || resume
        : resume;
      if (!onFailureRef.current(position))
        setError(message);
    };
    const watchStall = () => {
      if (stallTimeout !== undefined) return;
      stallTimeout = window.setTimeout(() => {
        const art = artRef.current;
        if (art && !art.video.paused && !art.video.ended)
          failPlayback("视频缓冲超时，请切换线路。");
      }, 30000);
    };
    const source = {
      fail: failPlayback,
      clearTimers,
      watchStall,
      resume,
      loaded: false,
      shouldPlay: true,
    };
    sourceRef.current = source;
    // 有些失效线路既不返回视频数据，也不触发 media error，需要限定首次加载时间。
    startupTimeout = window.setTimeout(
      () => failPlayback("视频加载超时，请切换线路。"),
      30000,
    );
    window.videoAPI
      .streamUrl(url, filterAds)
      .then(async (stream) => {
        // 播放器与 HLS 库按需加载，切集时沿用已经创建的播放器。
        const [{ default: Artplayer }, hlsModule] = await Promise.all([
          import("artplayer"),
          stream.isHls ? import("hls.js") : Promise.resolve(null),
        ]);
        if (cancelled) return;
        hlsTypeRef.current = hlsModule?.default || null;
        hlsRef.current?.destroy();
        hlsRef.current = null;
        const existing = artRef.current;
        if (existing) {
          existing.option.type = stream.isHls ? "m3u8" : "";
          existing.url = stream.url;
          return;
        }
        const container = playerRef.current;
        if (!container) return;
        const art = new Artplayer({
          container,
          url: stream.url,
          // 代理地址以 /stream 结尾，ArtPlayer 无法从后缀识别 HLS。
          ...(stream.isHls ? { type: "m3u8" as const } : {}),
          customType: {
            m3u8(video, source) {
              const Hls = hlsTypeRef.current;
              if (Hls?.isSupported()) {
                hlsRef.current = new Hls({
                  maxBufferLength:
                    window.videoAPI.platform === "android" ? 20 : 40,
                  enableWorker: true,
                });
                hlsRef.current.loadSource(source);
                hlsRef.current.attachMedia(video);
                hlsRef.current.on(Hls.Events.ERROR, (_, data) => {
                  if (data.fatal)
                    sourceRef.current?.fail(`视频加载失败：${data.details}`);
                });
              } else if (video.canPlayType("application/vnd.apple.mpegurl")) {
                video.src = source;
              } else {
                sourceRef.current?.fail("当前系统不支持 HLS 视频播放。");
              }
            },
          },
          lang: "zh-cn",
          theme: "#37c5e2",
          autoplay: true,
          playsInline: true,
          setting: true,
          playbackRate: true,
          pip: true,
          // 手机 WebView 使用网页全屏；电视由页面管理全屏和遥控器返回。
          fullscreen: window.videoAPI.platform !== "android",
          fullscreenWeb: !document.body.classList.contains("tv-body"),
        });
        artRef.current = art;
        playerInstance.current = art;
        const fullscreenTitle = document.createElement("div");
        fullscreenTitle.className = "player-fullscreen-title";
        fullscreenTitle.textContent = titleTextRef.current;
        art.template.$player.append(fullscreenTitle);
        fullscreenTitleRef.current = fullscreenTitle;
        // 控件使用 ArtPlayer 的右侧排序，35 紧跟在设置按钮（30）之后。
        art.controls.add({
          name: "nextEpisode",
          position: "right",
          index: 35,
          html: `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor"
            stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <polygon points="5 4 15 12 5 20 5 4"/>
            <line x1="19" x2="19" y1="5" y2="19"/>
          </svg>`,
          tooltip: "播放下一集",
          click: () => onNextRef.current(),
          mounted: (element) => element.setAttribute("aria-label", "下一集"),
        });
        art.template.$player.classList.toggle(
          "has-next-episode",
          hasNextRef.current,
        );
        if (window.videoAPI.platform !== "android") {
          art.controls.fullscreen?.addEventListener(
            "click",
            (event) => {
              if (!art?.fullscreenWeb) return;
              // 先把播放器放回页面，再请求系统全屏；全屏后移动节点会使浏览器立刻退出全屏。
              event.stopImmediatePropagation();
              art.fullscreenWeb = false;
              art.fullscreen = true;
            },
            true,
          );
        }
        if (window.videoAPI.platform === "android") {
          art.on("fullscreenWeb", (fullscreen) => {
            // ArtPlayer 已把播放器移到 body；页面保持原方向，播放器自己横向铺满。
            document.body.classList.toggle("player-web-fullscreen", fullscreen);
            // ArtPlayer 的进度条和触摸滑动依赖此运行时标记来交换横纵坐标。
            (art as unknown as { isRotate: boolean }).isRotate =
              fullscreen && window.matchMedia("(orientation: portrait)").matches;
            window.videoAPI.setVideoFullscreen(fullscreen);
          });
          if (!document.body.classList.contains("tv-body"))
            stopTouchGesturesRef.current = enableMobilePlaybackGestures(art);
        }
        art.on("video:loadedmetadata", () => {
          const current = sourceRef.current;
          if (current) current.loaded = true;
          const position = current?.resume || 0;
          if (position > 5 && position < art.duration - 15)
            art.currentTime = position;
        });
        art.on("video:timeupdate", () => {
          if (sourceRef.current?.loaded && art.currentTime > 1)
            onProgressRef.current(art.currentTime, art.duration || 0);
        });
        art.on("video:canplay", () => {
          const current = sourceRef.current;
          current?.clearTimers();
          if (current?.shouldPlay) {
            current.shouldPlay = false;
            void art.play().catch(() => {});
          }
        });
        art.on("video:playing", () => {
          sourceRef.current?.clearTimers();
        });
        // 播放途中若一直转圈且没有触发 error，也继续尝试右侧的其他线路。
        art.on("video:waiting", () => sourceRef.current?.watchStall());
        art.on("video:stalled", () => sourceRef.current?.watchStall());
        art.on("video:pause", () => {
          sourceRef.current?.clearTimers();
        });
        art.on("video:ended", () => onEndedRef.current());
        art.on("video:error", () => {
          const code = art?.video.error?.code;
          const message =
            code === MediaError.MEDIA_ERR_NETWORK
              ? "视频请求失败，请稍后重试或切换线路。"
              : code === MediaError.MEDIA_ERR_DECODE
                ? "视频解码失败，当前编码可能不受播放器支持。"
                : code === MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED
                  ? "该地址未返回可播放的视频流，可能是网页播放地址或不支持的格式。"
                  : "该线路暂时无法播放，请切换线路或集数。";
          sourceRef.current?.fail(message);
        });
      })
      .catch((error) => {
        failPlayback(error.message);
      });
    return () => {
      cancelled = true;
      clearTimers();
      if (sourceRef.current === source) sourceRef.current = null;
    };
  }, [url, filterAds, playbackSerial]);

  return (
    <div
      className={`video-shell ${tvFullscreen ? "tv-fullscreen" : ""}`}
      data-tv-focus={
        document.body.classList.contains("tv-body") ? "" : undefined
      }
      tabIndex={document.body.classList.contains("tv-body") ? 0 : undefined}
      aria-label="播放器：确定键暂停或继续，左右键快退或快进十秒"
    >
      <div ref={playerRef} className="artplayer-host" />
      {error && <div className="video-error">{error}</div>}
      {tvFullscreen && (
        <div className="tv-player-hint">
          确定键 暂停/播放 · 左右键 快退/快进 · 返回键 选集
        </div>
      )}
    </div>
  );
}

export default function App() {
  const isTv = document.body.classList.contains("tv-body");
  const [section, setSection] = useState<
    "home" | "custom" | "search" | "history"
  >("home");
  const [type, setType] = useState<CatalogType>("movie");
  const [tag, setTag] = useState("热门");
  const [animeSort, setAnimeSort] = useState<"rank" | "date" | "heat">("rank");
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
  const [customSourceUrl, setCustomSourceUrl] = useState(
    readStorage("custom-source-url", ""),
  );
  const [customCategories, setCustomCategories] = useState<SourceCategory[]>(
    [],
  );
  const [customCategoryId, setCustomCategoryId] = useState<number | null>(null);
  const [customPage, setCustomPage] = useState(1);
  const [customPageCount, setCustomPageCount] = useState(0);
  const [customItems, setCustomItems] = useState<SearchItem[]>([]);
  const [customLoading, setCustomLoading] = useState(false);
  const [customError, setCustomError] = useState("");
  const [customCategoryError, setCustomCategoryError] = useState("");
  const [customReload, setCustomReload] = useState(0);
  const [customSourceOpen, setCustomSourceOpen] = useState(false);
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
  const [testBatchSize, setTestBatchSize] = useState(() => {
    const saved = Number(readStorage("source-test-concurrency", 8));
    return Number.isInteger(saved) && saved >= 1 && saved <= 100 ? saved : 8;
  });
  const [theme, setTheme] = useState<"dark" | "light">(
    readStorage(
      "color-theme",
      "light",
    ),
  );
  const filterAds = true;
  const [autoplay, setAutoplay] = useState(readStorage("autoplay", true));
  const [history, setHistory] = useState<HistoryItem[]>(
    readStorage("history", []),
  );
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [settingsClosing, setSettingsClosing] = useState(false);
  const [sourceFormatOpen, setSourceFormatOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [recentSearches, setRecentSearches] = useState<string[]>(() =>
    readStorage<string[]>("recent-searches", []),
  );
  const [showRecentSearches, setShowRecentSearches] = useState(false);
  const [tvPlayerFullscreen, setTvPlayerFullscreen] = useState(false);
  const [searched, setSearched] = useState("");
  const [results, setResults] = useState<SearchItem[]>([]);
  const [failed, setFailed] = useState<string[]>([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const [searchProgress, setSearchProgress] = useState(0);
  const [selected, setSelected] = useState<SearchItem | null>(null);
  const [detailClosing, setDetailClosing] = useState(false);
  const [detail, setDetail] = useState<VodDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState("");
  const [line, setLine] = useState(0);
  const [episode, setEpisode] = useState<number | null>(null);
  const [episodeOrder, setEpisodeOrder] = useState<"ascending" | "descending">(
    "ascending",
  );
  const [devices, setDevices] = useState<Device[]>(readStorage("devices", []));
  const [castOpen, setCastOpen] = useState(false);
  const [castBusy, setCastBusy] = useState(false);
  const [castError, setCastError] = useState("");
  const [castWarnings, setCastWarnings] = useState<string[]>([]);
  const [castingDevice, setCastingDevice] = useState<Device | null>(null);
  const [pairingDevice, setPairingDevice] = useState<Device | null>(null);
  const [airplayPin, setAirplayPin] = useState("");
  const searchSerial = useRef(0);
  // 返回搜索前的列表时，保留分类和原来的浏览位置。
  const searchOrigin = useRef<{
    section: "home" | "custom" | "history";
    scrollTop: number;
  }>({ section: "home", scrollTop: 0 });
  const restoreScroll = useRef<number | null>(null);
  const testSerial = useRef(0);
  const detailSerial = useRef(0);
  const detailCloseTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const settingsCloseTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const progressSaved = useRef(0);
  const progressIdentity = useRef("");
  const castClock = useRef<{
    deviceId: string;
    url: string;
    position: number;
    at: number;
    duration: number;
  } | null>(null);
  const pageScroll = useRef<HTMLDivElement>(null);
  const detailScroll = useRef<HTMLDivElement>(null);
  const searchBox = useRef<HTMLDivElement>(null);
  const playerInstance = useRef<Artplayer | null>(null);
  const failedLines = useRef(new Set<number>());
  const fallbackPosition = useRef<number | null>(null);
  const [playbackSerial, setPlaybackSerial] = useState(0);
  const loadMoreTrigger = useRef<HTMLDivElement>(null);
  const customSourcePicker = useRef<HTMLDivElement>(null);
  const customCategoryCache = useRef(new Map<string, SourceCategory[]>());
  const loadedCustomCatalog = useRef("");
  const casting = castingDevice?.name || "";

  // 窄屏退出动画期间保留页面；其余设备和减少动态效果设置立即返回。
  const animatePageExit = () =>
    !isTv &&
    window.videoAPI.platform === "android" &&
    window.matchMedia(
      "(max-width: 700px) and (prefers-reduced-motion: no-preference)",
    ).matches;

  function closeSettings() {
    if (settingsClosing) return;
    if (!animatePageExit()) {
      setSettingsOpen(false);
      return;
    }
    setSettingsClosing(true);
    settingsCloseTimer.current = setTimeout(() => {
      setSettingsOpen(false);
      setSettingsClosing(false);
      settingsCloseTimer.current = null;
    }, 260);
  }

  function closeDetail() {
    if (detailClosing) return;
    ++detailSerial.current;
    if (castingDevice)
      void saveCastProgress().finally(() => window.videoAPI.castStop().catch(() => {}));
    setCastingDevice(null);
    if (!animatePageExit()) {
      setSelected(null);
      setDetail(null);
      setEpisode(null);
      return;
    }
    setDetailClosing(true);
    detailCloseTimer.current = setTimeout(() => {
      setSelected(null);
      setDetail(null);
      setEpisode(null);
      setDetailClosing(false);
      detailCloseTimer.current = null;
    }, 260);
  }

  useEffect(() => () => {
    if (detailCloseTimer.current) clearTimeout(detailCloseTimer.current);
    if (settingsCloseTimer.current) clearTimeout(settingsCloseTimer.current);
  }, []);
  const enabledSources = sources.filter(
    (source) => enabled.includes(source.url) && !source.isAdult,
  );
  const customSource =
    enabledSources.find((source) => source.url === customSourceUrl) ||
    enabledSources[0];

  useEffect(() => {
    if (window.videoAPI.platform !== "android" || isTv) return;
    const viewport = window.visualViewport;
    let fullHeight = Math.min(viewport?.height ?? window.innerHeight, window.innerHeight);
    let width = viewport?.width ?? window.innerWidth;
    const updateKeyboard = () => {
      const height = Math.min(viewport?.height ?? window.innerHeight, window.innerHeight);
      const currentWidth = viewport?.width ?? window.innerWidth;
      if (Math.abs(currentWidth - width) > 50) fullHeight = height;
      width = currentWidth;
      fullHeight = Math.max(fullHeight, height);
      const editing =
        document.activeElement instanceof HTMLInputElement ||
        document.activeElement instanceof HTMLTextAreaElement;
      // 软键盘会缩短 WebView 可视高度，底部导航此时应让位给输入法。
      document.body.classList.toggle(
        "keyboard-open",
        fullHeight - height > 120 &&
          (editing || document.body.classList.contains("keyboard-open")),
      );
    };
    viewport?.addEventListener("resize", updateKeyboard);
    window.addEventListener("resize", updateKeyboard);
    document.addEventListener("focusin", updateKeyboard);
    document.addEventListener("focusout", updateKeyboard);
    return () => {
      viewport?.removeEventListener("resize", updateKeyboard);
      window.removeEventListener("resize", updateKeyboard);
      document.removeEventListener("focusin", updateKeyboard);
      document.removeEventListener("focusout", updateKeyboard);
      document.body.classList.remove("keyboard-open");
    };
  }, [isTv]);

  useEffect(() => {
    if (window.videoAPI.platform !== "android") return;
    let active = true;
    let remove: (() => void) | undefined;
    // 安卓端隐藏页面返回按钮后，由系统返回键完成相同的资源清理。
    const goBack = () => {
      if (tvPlayerFullscreen) setTvPlayerFullscreen(false);
      else if (playerInstance.current?.fullscreenWeb)
        playerInstance.current.fullscreenWeb = false;
      else if (sourceFormatOpen) setSourceFormatOpen(false);
      else if (castOpen) setCastOpen(false);
      else if (settingsOpen) closeSettings();
      else if (showRecentSearches) setShowRecentSearches(false);
      else if (customSourceOpen) setCustomSourceOpen(false);
      else if (selected) closeDetail();
      else if (section === "search") {
        ++searchSerial.current;
        setSearchLoading(false);
        restoreScroll.current = searchOrigin.current.scrollTop;
        setSection(searchOrigin.current.section);
      } else if (section !== "home") setSection("home");
      else if (document.documentElement.dataset.harmony === "true")
        window.videoAPI.close();
      else void CapacitorApp.exitApp();
    };
    if (document.documentElement.dataset.harmony === "true") {
      window.addEventListener("harmony-back", goBack);
      remove = () => window.removeEventListener("harmony-back", goBack);
    } else {
      CapacitorApp.addListener("backButton", goBack).then((listener) => {
        if (active) remove = () => listener.remove();
        else listener.remove();
      });
    }
    // 部分电视遥控器将返回键送为网页 Escape/Backspace，而非 Android backButton。
    const onTvBack = (event: KeyboardEvent) => {
      if (!document.body.classList.contains("tv-body")) return;
      if (event.key !== "Escape" && event.key !== "Backspace") return;
      if (
        event.key === "Backspace" &&
        (event.target instanceof HTMLInputElement ||
          event.target instanceof HTMLTextAreaElement)
      )
        return;
      event.preventDefault();
      goBack();
    };
    document.addEventListener("keydown", onTvBack);
    return () => {
      active = false;
      remove?.();
      document.removeEventListener("keydown", onTvBack);
    };
  }, [
    tvPlayerFullscreen,
    sourceFormatOpen,
    castOpen,
    settingsOpen,
    settingsClosing,
    showRecentSearches,
    customSourceOpen,
    castingDevice,
    selected,
    detailClosing,
    detail,
    line,
    episode,
    section,
  ]);

  useEffect(() => {
    if (!isTv) return;
    // 电视选集后直接进入沉浸播放；返回键先退回线路和选集页面。
    setTvPlayerFullscreen(episode !== null && selected !== null);
  }, [isTv, episode, selected]);

  useEffect(() => {
    if (!tvPlayerFullscreen) return;
    const frame = requestAnimationFrame(() => {
      const player = document.querySelector<HTMLElement>(".tv-fullscreen");
      if (player) focusTvElement(player);
    });
    return () => cancelAnimationFrame(frame);
  }, [tvPlayerFullscreen, episode, detail]);

  useEffect(() => {
    if (!document.body.classList.contains("tv-body")) return;
    const onTvKeyDown = (event: KeyboardEvent) => {
      const player = playerInstance.current;
      if (
        ["MediaPlayPause", "MediaPlay", "MediaPause"].includes(event.key) &&
        player
      ) {
        event.preventDefault();
        if (
          event.key === "MediaPause" ||
          (event.key === "MediaPlayPause" && !player.video.paused)
        )
          player.video.pause();
        else void player.video.play().catch(() => {});
        return;
      }
      const target = event.target as HTMLElement;
      if (
        event.key === "Enter" &&
        target instanceof HTMLInputElement &&
        target.type === "checkbox"
      ) {
        event.preventDefault();
        target.click();
        return;
      }
      if (target.matches(".video-shell") && player) {
        if (event.key === "Enter" || event.key === " ") {
          event.preventDefault();
          if (player.video.paused) void player.video.play().catch(() => {});
          else player.video.pause();
          return;
        }
        if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
          event.preventDefault();
          player.currentTime = Math.max(
            0,
            Math.min(
              player.duration || Infinity,
              player.currentTime + (event.key === "ArrowRight" ? 10 : -10),
            ),
          );
          return;
        }
      }
      if (
        !["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"].includes(event.key)
      )
        return;
      if (
        (target instanceof HTMLInputElement ||
          target instanceof HTMLTextAreaElement) &&
        (event.key === "ArrowLeft" || event.key === "ArrowRight")
      )
        return;
      const scope =
        document.querySelector(".tv-fullscreen") ||
        document.querySelector(".source-format-backdrop .cast-modal") ||
        document.querySelector(".modal-backdrop .cast-modal") ||
        document.querySelector(".settings-drawer") ||
        document.querySelector(".detail-overlay") ||
        document.querySelector(".search-box.history-open") ||
        document.querySelector(".app-shell");
      if (!scope) return;
      event.preventDefault();
      moveTvFocus(scope, event.key);
    };
    document.addEventListener("keydown", onTvKeyDown, true);
    return () => document.removeEventListener("keydown", onTvKeyDown, true);
  }, []);

  useEffect(() => {
    if (!document.body.classList.contains("tv-body")) return;
    const frame = requestAnimationFrame(() => {
      const scope =
        document.querySelector(".source-format-backdrop .cast-modal") ||
        document.querySelector(".modal-backdrop .cast-modal") ||
        document.querySelector(".settings-drawer") ||
        document.querySelector(".detail-overlay");
      if (!scope) return;
      if (scope.contains(document.activeElement)) return;
      const preferred =
        scope.querySelector<HTMLElement>("[data-tv-autofocus]") ||
        scope.querySelector<HTMLElement>("button:not(:disabled)");
      if (preferred) focusTvElement(preferred);
    });
    return () => cancelAnimationFrame(frame);
  }, [
    selected,
    detailLoading,
    episode,
    settingsOpen,
    sourceFormatOpen,
    castOpen,
  ]);

  useEffect(() => {
    if (!isTv || section !== "search" || !results.length || selected) return;
    // 从最近搜索或海报进入结果页时，原按钮已卸载，焦点落到第一张海报。
    const frame = requestAnimationFrame(() => {
      if (document.activeElement !== document.body) return;
      const first = document.querySelector<HTMLElement>(
        ".search-results .poster-card",
      );
      if (first) focusTvElement(first);
    });
    return () => cancelAnimationFrame(frame);
  }, [isTv, section, results.length, selected]);

  useEffect(() => {
    if (!showRecentSearches) return;
    const closeOnOutsideClick = (event: PointerEvent) => {
      if (!searchBox.current?.contains(event.target as Node))
        setShowRecentSearches(false);
    };
    document.addEventListener("pointerdown", closeOnOutsideClick);
    return () =>
      document.removeEventListener("pointerdown", closeOnOutsideClick);
  }, [showRecentSearches]);

  useEffect(() => {
    if (customSource && customSource.url !== customSourceUrl) {
      setCustomSourceUrl(customSource.url);
      saveStorage("custom-source-url", customSource.url);
    }
  }, [customSource?.url, customSourceUrl]);

  useEffect(() => {
    if (!customSourceOpen) return;
    const closeOnOutsideClick = (event: PointerEvent) => {
      if (!customSourcePicker.current?.contains(event.target as Node))
        setCustomSourceOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === "Escape") setCustomSourceOpen(false);
    };
    document.addEventListener("pointerdown", closeOnOutsideClick);
    document.addEventListener("keydown", closeOnEscape);
    return () => {
      document.removeEventListener("pointerdown", closeOnOutsideClick);
      document.removeEventListener("keydown", closeOnEscape);
    };
  }, [customSourceOpen]);

  useEffect(() => {
    if (section !== "custom") setCustomSourceOpen(false);
  }, [section]);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    saveStorage("color-theme", theme);
    window.videoAPI.setTheme(theme);
  }, [theme]);

  useEffect(() => {
    // 鸿蒙状态栏位于网页外，按当前可见页面同步底色。
    if (document.documentElement.dataset.harmony !== "true") return;
    window.videoAPI.setSystemBarSurface?.(
      settingsOpen
        ? "settings"
        : selected || section === "search"
          ? "detail"
          : "page",
    );
  }, [section, selected, settingsOpen]);

  useEffect(() => {
    pageScroll.current?.scrollTo(0, restoreScroll.current ?? 0);
    restoreScroll.current = null;
  }, [section, type, tag, animeSort]);

  useEffect(() => {
    let cancelled = false;
    setCatalogLoading(true);
    setCatalogError("");
    window.videoAPI
      .catalog(type, tag, page, animeSort)
      .then((items) => {
        if (cancelled) return;
        setCatalog((previous) =>
          page === 0 ? items : [...previous, ...items],
        );
        setCatalogHasMore(items.length === (type === "anime" ? 20 : 24));
      })
      .catch((error) => {
        if (!cancelled)
          setCatalogError(
            `${type === "anime" ? "Bangumi" : "豆瓣"}推荐暂时不可用：${error.message}`,
          );
      })
      .finally(() => {
        if (!cancelled) setCatalogLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [type, tag, page, animeSort, catalogReload]);

  useEffect(() => {
    if (section !== "custom" || !customSource) return;
    const cacheKey = JSON.stringify([customSource.url, customReload]);
    const cached = customCategoryCache.current.get(cacheKey);
    if (cached) {
      // 从其他页面返回时直接恢复该点播源的分类和标签。
      setCustomCategories(cached);
      setCustomCategoryError("");
      return;
    }
    let cancelled = false;
    setCustomCategories([]);
    setCustomCategoryError("");
    window.videoAPI
      .sourceCategories(customSource.url)
      .then((items) => {
        if (cancelled) return;
        customCategoryCache.current.set(cacheKey, items);
        setCustomCategories(items);
      })
      .catch((error) => {
        if (!cancelled) setCustomCategoryError(error.message);
      });
    return () => {
      cancelled = true;
    };
  }, [section, customSource?.url, customReload]);

  useEffect(() => {
    if (section !== "custom" || !customSource) return;
    const requestKey = JSON.stringify([
      customSource.url,
      customCategoryId,
      customPage,
      customReload,
    ]);
    // 同一筛选页返回时沿用已有列表，避免清空后再次请求。
    if (loadedCustomCatalog.current === requestKey) return;
    let cancelled = false;
    setCustomLoading(true);
    setCustomError("");
    if (customPage === 1) setCustomItems([]);
    window.videoAPI
      .sourceCatalog(customSource, customCategoryId, customPage)
      .then(({ items, pageCount }) => {
        if (cancelled) return;
        setCustomItems((previous) => {
          if (customPage === 1) return items;
          // 从详情或搜索返回时会重取当前页，避免把同一批资源重复追加。
          const seen = new Set(previous.map((item) => item.id));
          return [...previous, ...items.filter((item) => !seen.has(item.id))];
        });
        setCustomPageCount(pageCount);
        loadedCustomCatalog.current = requestKey;
      })
      .catch((error) => {
        if (!cancelled) setCustomError(error.message);
      })
      .finally(() => {
        if (!cancelled) setCustomLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [section, customSource?.url, customCategoryId, customPage, customReload]);

  useEffect(() => {
    const loadCatalog =
      section === "home" &&
      !selected &&
      !catalogLoading &&
      !catalogError &&
      catalogHasMore &&
      catalog.length > 0;
    const loadCustom =
      section === "custom" &&
      !selected &&
      !customLoading &&
      !customError &&
      loadedCustomCatalog.current ===
        JSON.stringify([
          customSource?.url,
          customCategoryId,
          customPage,
          customReload,
        ]) &&
      customItems.length > 0 &&
      customPage < customPageCount;
    const root = pageScroll.current;
    const trigger = loadMoreTrigger.current;
    if ((!loadCatalog && !loadCustom) || !root || !trigger) return;

    // 下一页进入滚动区域附近时预加载；立即置为加载中，避免重复触发跳页。
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (!entry.isIntersecting) return;
        observer.disconnect();
        if (loadCatalog) {
          setCatalogLoading(true);
          setPage((current) => current + 1);
        } else {
          setCustomLoading(true);
          setCustomPage((current) => current + 1);
        }
      },
      { root, rootMargin: "0px 0px 240px 0px" },
    );
    observer.observe(trigger);
    return () => observer.disconnect();
  }, [
    section,
    selected,
    page,
    customPage,
    catalogLoading,
    catalogError,
    catalogHasMore,
    catalog.length,
    customLoading,
    customError,
    customItems.length,
    customPageCount,
    customSource?.url,
    customCategoryId,
    customReload,
  ]);

  useEffect(
    () =>
      window.videoAPI.onCastEnded(async ({ deviceId, error }) => {
        if (!castingDevice || castingDevice.id !== deviceId) return;
        await saveCastProgress();
        if (error) {
          setCastError(`电视播放中断：${error}`);
          setCastingDevice(null);
          return;
        }
        const nextIndex = (episode ?? -1) + 1;
        const next = detail?.lines[line]?.episodes[nextIndex];
        if (!autoplay || !next || !detail) {
          setCastingDevice(null);
          setEpisode(null);
          return;
        }
        // 电视端结束当前集后，继续把下一集交给同一台设备。
        setEpisode(nextIndex);
        setCastBusy(true);
        setCastError("");
        try {
          const ok = await window.videoAPI.cast(
            castingDevice,
            next.url,
            `${detail.title} · ${next.label}`,
            filterAds,
            0,
          );
          if (!ok) throw new Error("电视未接受下一集播放请求");
          castClock.current = {
            deviceId: castingDevice.id,
            url: next.url,
            position: 0,
            at: Date.now(),
            duration: 0,
          };
        } catch (castError) {
          setCastError(`自动换集投屏失败：${(castError as Error).message}`);
          setCastingDevice(null);
        } finally {
          setCastBusy(false);
        }
      }),
    [castingDevice, detail, line, episode, autoplay, filterAds],
  );

  useEffect(() => {
    const currentUrl =
      episode === null ? null : detail?.lines[line]?.episodes[episode]?.url;
    if (!castingDevice || !selected || !currentUrl) return;
    let active = true;
    let polling = false;
    const poll = async () => {
      if (polling) return;
      polling = true;
      try {
        const progress = await window.videoAPI.castProgress().catch(() => null);
        if (!active) return;
        const current = updateCastProgress(castingDevice.id, currentUrl, progress);
        if (current) recordProgress(current.position, current.duration);
      } finally {
        polling = false;
      }
    };
    // 电视端控制播放期间，本机播放器已卸载，定时用电视的实际进度更新记录。
    void poll();
    const timer = window.setInterval(poll, 5000);
    return () => {
      active = false;
      window.clearInterval(timer);
    };
  }, [castingDevice, selected, detail, line, episode]);

  async function searchFor(value: string) {
    const term = value.trim();
    if (!term) return;
    // 从推荐、输入框或历史记录发起的搜索都进入同一份最近记录。
    const nextSearches = [
      term,
      ...recentSearches.filter(
        (item) => item.toLocaleLowerCase() !== term.toLocaleLowerCase(),
      ),
    ].slice(0, 10);
    setRecentSearches(nextSearches);
    saveStorage("recent-searches", nextSearches);
    setShowRecentSearches(false);
    if (section !== "search")
      searchOrigin.current = {
        section,
        scrollTop: pageScroll.current?.scrollTop ?? 0,
      };
    const serial = ++searchSerial.current;
    setQuery(term);
    setSearched(term);
    // 窄屏安卓进入结果页后会隐藏搜索栏，先收起软键盘。
    if (window.videoAPI.platform === "android" && window.innerWidth <= 700)
      searchBox.current?.querySelector("input")?.blur();
    setSection("search");
    pageScroll.current?.scrollTo(0, 0);
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
        (completed, total, items, failedSource) => {
          if (serial !== searchSerial.current) return;
          setSearchProgress(Math.round((completed / total) * 100));
          if (items.length > 0)
            setResults((previous) => {
              const known = new Set(
                previous.map((item) => `${item.sourceUrl}:${item.id}`),
              );
              return [
                ...previous,
                ...items.filter((item) => {
                  const key = `${item.sourceUrl}:${item.id}`;
                  if (known.has(key)) return false;
                  known.add(key);
                  return true;
                }),
              ];
            });
          if (failedSource)
            setFailed((previous) =>
              previous.includes(failedSource)
                ? previous
                : [...previous, failedSource],
            );
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
    // 一次测活使用开始时的并发数；设置变更只影响下一次测活。
    const batchSize = testBatchSize;
    for (let index = 0; index < candidates.length; index += batchSize) {
      if (runId !== testSerial.current) break;
      await Promise.all(
        candidates.slice(index, index + batchSize).map(async (source) => {
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
    if (detailCloseTimer.current) clearTimeout(detailCloseTimer.current);
    detailCloseTimer.current = null;
    setDetailClosing(false);
    const serial = ++detailSerial.current;
    failedLines.current.clear();
    fallbackPosition.current = null;
    setEpisodeOrder("ascending");
    const cachedDetail = resumeItem?.detail;
    const canResume = Boolean(
      cachedDetail?.lines[resumeItem?.line ?? 0]?.episodes[
        resumeItem?.episode ?? 0
      ]?.url,
    );
    setSelected(item);
    // 播放记录已有完整线路时先进入播放器，后台再获取最新线路。
    setDetail(canResume && cachedDetail ? cachedDetail : null);
    setDetailError("");
    setDetailLoading(!canResume);
    setCastOpen(false);
    if (castingDevice)
      void saveCastProgress().finally(() => window.videoAPI.castStop().catch(() => {}));
    setCastingDevice(null);
    setLine(resumeItem?.line || 0);
    setEpisode(resumeItem ? resumeItem.episode : null);
    try {
      const freshDetail = await window.videoAPI.detail(item.sourceUrl, item.id);
      if (serial !== detailSerial.current) return;
      setDetail(freshDetail);
      if (
        resumeItem &&
        !freshDetail.lines[resumeItem.line]?.episodes[resumeItem.episode]
      ) {
        setLine(0);
        setEpisode(freshDetail.lines[0]?.episodes.length ? 0 : null);
      }
      if (resumeItem) {
        setHistory((previous) => {
          const next = previous.map((entry) =>
            entry.key === resumeItem.key
              ? { ...entry, detail: freshDetail }
              : entry,
          );
          saveStorage("history", next);
          return next;
        });
      }
    } catch (error) {
      if (serial !== detailSerial.current || canResume) return;
      const message = (error as Error).message;
      setDetailError(
        /TimeoutError|aborted due to timeout|timed out/i.test(message)
          ? "点播源响应超时，请重试或返回列表选择其他来源。"
          : message,
      );
    } finally {
      if (serial === detailSerial.current) setDetailLoading(false);
    }
  }

  function changeType(next: CatalogType) {
    let nextTag = "热门";
    if (next === "anime") nextTag = "TV";
    if (next === "variety") nextTag = "综艺";
    if (
      next === type &&
      tag === nextTag &&
      (next !== "anime" || animeSort === "rank") &&
      page === 0
    ) {
      setSection("home");
      setSelected(null);
      return;
    }
    setType(next);
    setTag(nextTag);
    setAnimeSort("rank");
    setPage(0);
    setCatalog([]);
    setCatalogLoading(true);
    setCatalogError("");
    setCatalogHasMore(true);
    setSection("home");
    setSelected(null);
  }
  function changeTag(next: string) {
    // 切换到不同来源的子标签时，回到该来源都支持的高分排序。
    if (next === tag) return;
    setTag(next);
    if (
      type === "anime" &&
      (ANIME_FORMAT_TAGS.includes(next)
        ? animeSort === "heat"
        : animeSort === "date")
    )
      setAnimeSort("rank");
    setPage(0);
    setCatalog([]);
    setCatalogLoading(true);
    setCatalogError("");
    setCatalogHasMore(true);
  }
  function changeAnimeSort(next: "rank" | "date" | "heat") {
    if (next === animeSort) return;
    setAnimeSort(next);
    setPage(0);
    setCatalog([]);
    setCatalogLoading(true);
    setCatalogError("");
    setCatalogHasMore(true);
  }

  function updateCastProgress(
    deviceId: string,
    url: string,
    reported: Awaited<ReturnType<typeof window.videoAPI.castProgress>>,
  ) {
    const clock = castClock.current;
    if (!clock || clock.deviceId !== deviceId || clock.url !== url) return null;
    const now = Date.now();
    // 部分电视不报告时间或只报告 0；正常播放时从上次可信位置推算。
    const estimated = clock.position + Math.max(0, now - clock.at) / 1000;
    const matches = reported?.deviceId === deviceId && reported.url === url;
    const position = matches && reported.position > 1 && reported.position >= clock.position - 15
      ? reported.position
      : estimated;
    const duration = matches && reported.duration > 0 ? reported.duration : clock.duration;
    castClock.current = { ...clock, position, duration, at: now };
    return { position, duration };
  }

  async function saveCastProgress() {
    const url = episode === null ? null : detail?.lines[line]?.episodes[episode]?.url;
    if (!castingDevice || !url) return null;
    const reported = await window.videoAPI.castProgress().catch(() => null);
    const current = updateCastProgress(castingDevice.id, url, reported);
    if (current) recordProgress(current.position, current.duration, true);
    return current;
  }

  function recordProgress(position: number, duration: number, force = false) {
    if (!selected || episode === null || position <= 1) return;
    const identity = `${selected.sourceUrl}:${selected.id}:${line}:${episode}`;
    if (identity !== progressIdentity.current) {
      progressIdentity.current = identity;
      // 新集的第一条进度不应被上一集的保存间隔挡住。
      progressSaved.current = 0;
    }
    if (!force &&
      Date.now() - progressSaved.current <
      4000
    ) return;
    progressSaved.current = Date.now();
    const item: HistoryItem = {
      key: `${selected.sourceUrl}:${selected.id}`,
      item: selected,
      detail: detail ?? undefined,
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

  function playNextEpisode() {
    if (
      detail &&
      episode !== null &&
      episode + 1 < detail.lines[line].episodes.length
    ) {
      failedLines.current.clear();
      fallbackPosition.current = null;
      setEpisode(episode + 1);
    }
  }

  function nextEpisode() {
    if (autoplay) playNextEpisode();
  }

  async function scanDevices() {
    setCastOpen(true);
    setCastBusy(true);
    setCastError("");
    setCastWarnings([]);
    setPairingDevice(null);
    try {
      const found = await window.videoAPI.devices();
      setDevices(found.items);
      setCastWarnings(found.warnings);
    } catch (error) {
      let message = "";
      if (error instanceof Error) message = error.message;
      else if (typeof error === "string") message = error;
      else if (
        error &&
        typeof error === "object" &&
        "message" in error &&
        typeof error.message === "string"
      ) {
        message = error.message;
      }
      if (!message || message.includes("[object"))
        message = "请连接电视所在的 Wi-Fi 局域网后重试。";
      setCastError(`发现电视失败：${message}`);
    } finally {
      setCastBusy(false);
    }
  }

  async function castEpisode(device: Device, episodeIndex: number) {
    const current = detail?.lines[line]?.episodes[episodeIndex];
    if (!current || !detail) throw new Error("当前集数不可播放");
    const startPosition = castStartPosition(episodeIndex);
    const ok = await window.videoAPI.cast(
      device,
      current.url,
      `${detail.title} · ${current.label}`,
      filterAds,
      startPosition,
    );
    if (!ok) throw new Error("电视未接受播放请求");
    // 投屏开始后的第一条电视进度不要受本机播放的保存间隔影响。
    progressSaved.current = 0;
    const playerDuration = episodeIndex === episode ? playerInstance.current?.duration || 0 : 0;
    castClock.current = {
      deviceId: device.id,
      url: current.url,
      position: device.type === "airplay" ? 0 : startPosition,
      at: Date.now(),
      duration: Number.isFinite(playerDuration) && playerDuration > 0
        ? playerDuration
        : selectedHistory?.line === line && selectedHistory.episode === episodeIndex
          ? selectedHistory.duration
          : 0,
    };
    return startPosition;
  }

  async function castTo(device: Device) {
    setCastBusy(true);
    setCastError("");
    try {
      if (device.type === "airplay" && !device.paired) {
        setPairingDevice(device);
        setAirplayPin("");
        await window.videoAPI.airplayPairBegin(device.id);
        return;
      }
      const startPosition = await castEpisode(device, episode ?? 0);
      setCastingDevice(device);
      setCastOpen(false);
      if (device.type === "airplay" && startPosition > 0)
        setCastError("AirPlay 当前不支持从续播进度开始，已从头投放。");
    } catch (error) {
      if (device.type === "airplay") setPairingDevice(null);
      setCastError(`投屏失败：${(error as Error).message}`);
    } finally {
      setCastBusy(false);
    }
  }

  function castStartPosition(episodeIndex: number) {
    const livePosition =
      episodeIndex === episode ? playerInstance.current?.currentTime || 0 : 0;
    if (livePosition > 0) return livePosition;
    return selectedHistory?.line === line &&
      selectedHistory.episode === episodeIndex &&
      selectedHistory.position > 5 &&
      (!selectedHistory.duration ||
        selectedHistory.position < selectedHistory.duration - 15)
      ? selectedHistory.position
      : 0;
  }

  async function selectEpisode(index: number) {
    if (castingDevice && index === episode) return;
    failedLines.current.clear();
    fallbackPosition.current = null;
    setEpisode(index);
    setPlaybackSerial((current) => current + 1);
    // 选集面板可能在长页面底部，切换后直接露出播放器。
    detailScroll.current?.scrollTo(0, 0);
    if (!castingDevice) return;
    setCastBusy(true);
    setCastError("");
    try {
      const startPosition = await castEpisode(castingDevice, index);
      if (castingDevice.type === "airplay" && startPosition > 0)
        setCastError("AirPlay 当前不支持从续播进度开始，已从头投放。");
    } catch (error) {
      setCastError(`切换集数投屏失败：${(error as Error).message}`);
      setCastingDevice(null);
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

  async function stopCasting() {
    setCastBusy(true);
    setCastError("");
    try {
      const current = await saveCastProgress();
      await window.videoAPI.castStop();
      fallbackPosition.current = current?.position ?? null;
      castClock.current = null;
      setCastingDevice(null);
    } catch (error) {
      setCastError(`停止投屏失败：${(error as Error).message}`);
    } finally {
      setCastBusy(false);
    }
  }

  const featured = catalog[0];
  const categoryName = {
    movie: "电影",
    tv: "剧集",
    anime: "番剧",
    variety: "综艺",
  }[type];
  const testTotal = sources.filter((source) => !source.isAdult).length;
  const playing = detail?.lines[line]?.episodes[episode ?? 0];
  // 三列按钮约能放下五个汉字；更长的集名改为两列显示。
  const hasLongEpisodeLabels = detail?.lines[line]?.episodes.some(
    (item) => item.label.length > 5,
  );
  const displayedEpisodes =
    detail?.lines[line]?.episodes.map((item, index) => ({ item, index })) ?? [];
  if (episodeOrder === "descending") displayedEpisodes.reverse();
  const selectedHistory = selected
    ? history.find(
        (item) => item.key === `${selected.sourceUrl}:${selected.id}`,
      )
    : undefined;
  const selectedCustomCategory = customCategories.find(
    (category) => category.id === customCategoryId,
  );
  const activeCustomParentId =
    selectedCustomCategory?.parentId || selectedCustomCategory?.id;
  const customRootCategories = customCategories.filter(
    (category) =>
      category.parentId === 0 ||
      !customCategories.some((parent) => parent.id === category.parentId),
  );
  const customChildCategories = customCategories.filter(
    (category) => category.parentId === activeCustomParentId,
  );

  return (
    <div className={`app-shell ${theme} platform-${window.videoAPI.platform}`}>
      {window.videoAPI.platform === "win32" && (
        <div className="window-controls" aria-label="窗口控制">
          <button
            title="最小化"
            aria-label="最小化"
            onClick={() => window.videoAPI.minimize()}
          >
            <Minus size={18} />
          </button>
          <button
            title="最大化或还原"
            aria-label="最大化或还原"
            onClick={() => window.videoAPI.maximize()}
          >
            <Square size={14} />
          </button>
          <button
            className="window-close"
            title="关闭"
            aria-label="关闭"
            onClick={() => window.videoAPI.close()}
          >
            <X size={18} />
          </button>
        </div>
      )}
      <aside className="sidebar">
        <button
          className={`brand ${section === "home" ? "active" : ""}`}
          aria-label="首页"
          onClick={() => {
            setSection("home");
            setSelected(null);
          }}
        >
          <span className="brand-mark">
            <Play size={17} fill="currentColor" />
          </span>
          <span className="brand-title">
            影窗<strong>YINGCHUANG</strong>
          </span>
          <span className="brand-mobile-label">首页</span>
        </button>
        <div className="nav-caption">发现</div>
        <button
          className={`nav-item mobile-category-nav ${section === "home" && type === "movie" ? "active" : ""}`}
          onClick={() => changeType("movie")}
        >
          <Film size={19} /> 电影
        </button>
        <button
          className={`nav-item mobile-category-nav ${section === "home" && type === "tv" ? "active" : ""}`}
          onClick={() => changeType("tv")}
        >
          <Tv size={19} /> 电视剧
        </button>
        <button
          className={`nav-item mobile-category-nav ${section === "home" && type === "variety" ? "active" : ""}`}
          onClick={() => changeType("variety")}
        >
          <Clapperboard size={19} /> 综艺
        </button>
        <button
          className={`nav-item ${section === "custom" ? "active" : ""}`}
          aria-label="自定义点播源"
          onClick={() => {
            setSection("custom");
            setSelected(null);
          }}
        >
          <ListFilter size={19} />
          <span className="nav-label-desktop">自定义</span>
          <span className="nav-label-mobile">片源</span>
        </button>
        <div className="nav-caption library-caption">我的片单</div>
        <button
          className={`nav-item ${section === "history" ? "active" : ""}`}
          aria-label="播放记录"
          onClick={() => {
            setSection("history");
            setSelected(null);
          }}
        >
          <History size={19} />
          <span className="nav-label-desktop">播放记录</span>
          <span className="nav-label-mobile">历史</span>{" "}
          <span className="nav-count">{history.length}</span>
        </button>
        <button
          className="nav-item"
          aria-label="播放设置"
          onClick={() => setSettingsOpen(true)}
        >
          <Settings2 size={19} />
          <span className="nav-label-desktop">播放设置</span>
          <span className="nav-label-mobile">设置</span>
        </button>
        <div className="sidebar-bottom">
          <div className="source-status">
            <span className="status-dot" /> {enabled.length} 个点播源已启用
          </div>
          <p>发现值得看的每一部作品</p>
        </div>
      </aside>

      <div className="main-area">
        <header
          className={`topbar ${section === "search" || selected ? "mobile-search-hidden" : ""}`}
        >
          <span className="topbar-caption">DISCOVER YOUR NEXT FAVORITE</span>
          <div
            className={`search-box ${showRecentSearches && recentSearches.length ? "history-open" : ""}`}
            ref={searchBox}
          >
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
                onFocus={() => setShowRecentSearches(true)}
                onKeyDown={(event) => {
                  if (event.key === "Escape") setShowRecentSearches(false);
                  if (
                    event.key === "ArrowDown" &&
                    showRecentSearches &&
                    recentSearches.length
                  ) {
                    event.preventDefault();
                    searchBox.current
                      ?.querySelector<HTMLButtonElement>(".recent-search-term")
                      ?.focus();
                  }
                }}
                placeholder="搜索电影、剧集、综艺..."
                aria-label="搜索影视作品"
                aria-expanded={showRecentSearches && recentSearches.length > 0}
                aria-controls="recent-searches"
              />
              <button type="submit">搜索</button>
            </form>
            {showRecentSearches && recentSearches.length > 0 && (
              <div
                className="recent-searches"
                id="recent-searches"
                aria-label="最近搜索"
              >
                <div className="recent-searches-heading">
                  <span>
                    <Clock3 size={15} /> 最近搜索{" "}
                    <small>{recentSearches.length}/10</small>
                  </span>
                  <button
                    type="button"
                    onClick={() => {
                      setRecentSearches([]);
                      saveStorage("recent-searches", []);
                      setShowRecentSearches(false);
                      if (isTv)
                        requestAnimationFrame(() =>
                          searchBox.current
                            ?.querySelector<HTMLInputElement>("input")
                            ?.focus(),
                        );
                    }}
                  >
                    清空记录
                  </button>
                </div>
                <div className="recent-searches-list">
                  {recentSearches.map((term) => (
                    <div className="recent-search-row" key={term}>
                      <button
                        className="recent-search-term"
                        type="button"
                        onClick={() => searchFor(term)}
                        title={`搜索 ${term}`}
                      >
                        <Search size={15} /> <span>{term}</span>
                      </button>
                      <button
                        className="recent-search-delete"
                        type="button"
                        aria-label={`删除搜索记录 ${term}`}
                        title={`删除 ${term}`}
                        onClick={() => {
                          const index = recentSearches.indexOf(term);
                          const next = recentSearches.filter(
                            (item) => item !== term,
                          );
                          setRecentSearches(next);
                          saveStorage("recent-searches", next);
                          if (!next.length) setShowRecentSearches(false);
                          if (isTv)
                            requestAnimationFrame(() => {
                              const buttons =
                                searchBox.current?.querySelectorAll<HTMLElement>(
                                  ".recent-search-delete",
                                );
                              if (buttons?.length)
                                focusTvElement(
                                  buttons[Math.min(index, buttons.length - 1)],
                                );
                              else
                                searchBox.current
                                  ?.querySelector<HTMLInputElement>("input")
                                  ?.focus();
                            });
                        }}
                      >
                        <X size={15} />
                      </button>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
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
        {section === "home" && (
          <nav className="mobile-channel-tabs" aria-label="内容分类">
            {(
              [
                ["movie", "电影"],
                ["tv", "电视剧"],
                ["variety", "综艺"],
              ] as const
            ).map(([value, label]) => (
              <button
                key={value}
                className={type === value ? "active" : ""}
                onClick={() => changeType(value)}
              >
                {label}
              </button>
            ))}
          </nav>
        )}

        <div className="page-scroll" ref={pageScroll}>
          <main className="content">
            {section === "home" && (
              <>
                <section className="hero">
                  <div className="hero-glow" />
                  <div className="hero-copy">
                    <div className="eyebrow">
                      <Sparkles size={14} />{" "}
                      豆瓣精选 · 每日发现
                    </div>
                    <h1>
                      光影之间，
                      <br />
                      <em>遇见好故事。</em>
                    </h1>
                    <p>
                      电影、剧集与综艺，随心探索。想看的，点开就能找。
                    </p>
                    <button
                      className="hero-button"
                      onClick={() => featured && searchFor(featured.title)}
                      disabled={!featured}
                    >
                      <Play size={16} fill="currentColor" /> 看看推荐{" "}
                      <ArrowRight size={17} />
                    </button>
                  </div>
                  {featured && (
                    <HeroPoster
                      item={featured}
                      ratingSource="豆瓣"
                    />
                  )}
                </section>
                <section className="catalog-section">
                  <div className="section-heading">
                    <div>
                      <span className="section-kicker">
                        EXPLORE THE COLLECTION
                      </span>
                      <h2>
                        {categoryName}推荐 <span>精选好内容</span>
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
                      <button
                        className={type === "variety" ? "selected" : ""}
                        onClick={() => changeType("variety")}
                      >
                        综艺
                      </button>
                    </div>
                  </div>
                  {(type === "movie" || type === "tv") && (
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
                  )}
                  {type === "anime" && (
                    <div className="anime-filters">
                      <div className="anime-filter-line">
                        <span>形式</span>
                        <div className="tag-list">
                          {ANIME_FORMAT_TAGS.map((name) => (
                            <button
                              key={name}
                              className={tag === name ? "active" : ""}
                              onClick={() => changeTag(name)}
                            >
                              {name}
                            </button>
                          ))}
                        </div>
                      </div>
                      <div className="anime-filter-line">
                        <span>题材</span>
                        <div className="tag-list">
                          {ANIME_GENRE_TAGS.map((name) => (
                            <button
                              key={name}
                              className={tag === name ? "active" : ""}
                              onClick={() => changeTag(name)}
                            >
                              {name}
                            </button>
                          ))}
                        </div>
                      </div>
                      <div className="anime-filter-line">
                        <span>排序</span>
                        <div className="tag-list">
                          {(ANIME_FORMAT_TAGS.includes(tag)
                            ? ANIME_FORMAT_SORTS
                            : ANIME_GENRE_SORTS
                          ).map(({ value, label }) => (
                            <button
                              key={value}
                              className={animeSort === value ? "active" : ""}
                              onClick={() => changeAnimeSort(value)}
                            >
                              {label}
                            </button>
                          ))}
                        </div>
                      </div>
                    </div>
                  )}
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
                      <LoaderCircle className="spin" size={20} />{" "}
                      正在加载推荐...
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
                      <div
                        ref={loadMoreTrigger}
                        className="load-more-trigger"
                      />
                    )}
                </section>
              </>
            )}

            {section === "custom" && (
              <section className="results-section custom-section">
                <div className="section-heading">
                  <div>
                    <span className="section-kicker">YOUR VOD SOURCES</span>
                    <h2>
                      自定义 <span>点播源最近更新</span>
                    </h2>
                  </div>
                  {customSource && (
                    <div
                      className="custom-source-picker"
                      ref={customSourcePicker}
                    >
                      <span>点播源</span>
                      <button
                        className="custom-source-trigger"
                        type="button"
                        aria-label={`选择点播源，当前：${customSource.name}`}
                        aria-haspopup="listbox"
                        aria-expanded={customSourceOpen}
                        onClick={() => setCustomSourceOpen((open) => !open)}
                      >
                        <span className="custom-source-dot" />
                        <strong>{customSource.name}</strong>
                        <ChevronDown size={16} />
                      </button>
                      {customSourceOpen && (
                        <div
                          className="custom-source-menu"
                          role="listbox"
                          aria-label="已启用的点播源"
                        >
                          <div className="custom-source-menu-title">
                            已启用点播源 · {enabledSources.length}
                          </div>
                          {enabledSources.map((source) => (
                            <button
                              key={source.url}
                              type="button"
                              role="option"
                              aria-selected={source.url === customSource.url}
                              className={
                                source.url === customSource.url
                                  ? "selected"
                                  : ""
                              }
                              onClick={() => {
                                setCustomSourceOpen(false);
                                if (source.url === customSource.url) return;
                                setCustomSourceUrl(source.url);
                                saveStorage("custom-source-url", source.url);
                                setCustomCategories(
                                  customCategoryCache.current.get(
                                    JSON.stringify([source.url, customReload]),
                                  ) || [],
                                );
                                setCustomCategoryId(null);
                                setCustomPage(1);
                              }}
                            >
                              <span>{source.name}</span>
                              {source.url === customSource.url && (
                                <Check size={16} />
                              )}
                            </button>
                          ))}
                        </div>
                      )}
                    </div>
                  )}
                </div>
                {!customSource ? (
                  <div className="empty">
                    <ListFilter size={38} />
                    <h3>还没有启用的点播源</h3>
                    <p>在播放设置中导入并启用点播源后，可浏览最近更新。</p>
                    <button
                      className="primary-button"
                      onClick={() => setSettingsOpen(true)}
                    >
                      打开播放设置
                    </button>
                  </div>
                ) : (
                  <>
                    {customCategoryError && (
                      <div className="notice error">
                        分类标签加载失败：{customCategoryError}{" "}
                        <button
                          onClick={() =>
                            setCustomReload((previous) => previous + 1)
                          }
                        >
                          重试
                        </button>
                      </div>
                    )}
                    {customCategories.length > 0 && (
                      <div className="custom-filters">
                        <div className="custom-filter-line">
                          <span>分类</span>
                          <div className="tag-list">
                            <button
                              className={
                                customCategoryId === null ? "active" : ""
                              }
                              onClick={() => {
                                setCustomCategoryId(null);
                                setCustomPage(1);
                              }}
                            >
                              全部
                            </button>
                            {customRootCategories.map((category) => (
                              <button
                                key={category.id}
                                className={
                                  activeCustomParentId === category.id
                                    ? "active"
                                    : ""
                                }
                                onClick={() => {
                                  // 父分类通常没有影片，直接进入它的第一个子标签。
                                  const firstTag = customCategories.find(
                                    (item) => item.parentId === category.id,
                                  );
                                  setCustomCategoryId(
                                    firstTag?.id ?? category.id,
                                  );
                                  setCustomPage(1);
                                }}
                              >
                                {category.name}
                              </button>
                            ))}
                          </div>
                        </div>
                        {customChildCategories.length > 0 && (
                          <div className="custom-filter-line">
                            <span>标签</span>
                            <div className="tag-list">
                              {customChildCategories.map((category) => (
                                <button
                                  key={category.id}
                                  className={
                                    customCategoryId === category.id
                                      ? "active"
                                      : ""
                                  }
                                  onClick={() => {
                                    setCustomCategoryId(category.id);
                                    setCustomPage(1);
                                  }}
                                >
                                  {category.name}
                                </button>
                              ))}
                            </div>
                          </div>
                        )}
                      </div>
                    )}
                    {customError && (
                      <div className="notice error">
                        最近更新加载失败：{customError}{" "}
                        <button
                          onClick={() =>
                            setCustomReload((previous) => previous + 1)
                          }
                        >
                          重试
                        </button>
                      </div>
                    )}
                    <div className="poster-grid">
                      {customItems.map((item) => (
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
                          <div className="poster-info custom-poster-info">
                            <strong title={item.title}>{item.title}</strong>
                            <small title={`${item.type} ${item.remarks}`}>
                              {[item.type, item.remarks]
                                .filter(Boolean)
                                .join(" · ") || "暂无更新说明"}
                            </small>
                            {item.updatedAt && <small>{item.updatedAt}</small>}
                          </div>
                        </button>
                      ))}
                    </div>
                    {customLoading && (
                      <div className="loading">
                        <LoaderCircle className="spin" size={20} />{" "}
                        正在加载最近更新...
                      </div>
                    )}
                    {!customLoading &&
                      !customError &&
                      customItems.length === 0 && (
                        <div className="empty">
                          <Film size={36} />
                          <h3>这个分类暂无资源</h3>
                          <p>可以切换标签或点播源。</p>
                        </div>
                      )}
                    {!customLoading &&
                      !customError &&
                      customItems.length > 0 &&
                      customPage < customPageCount && (
                        <div
                          ref={loadMoreTrigger}
                          className="load-more-trigger"
                        />
                      )}
                  </>
                )}
              </section>
            )}

            {section === "search" && (
              <section className="results-section search-results">
                {window.videoAPI.platform !== "android" && (
                  <div className="search-toolbar">
                    <button
                      type="button"
                      onClick={() => {
                        ++searchSerial.current;
                        setSearchLoading(false);
                        restoreScroll.current = searchOrigin.current.scrollTop;
                        setSection(searchOrigin.current.section);
                      }}
                    >
                      <ArrowLeft size={17} /> 返回列表
                    </button>
                    <button
                      type="button"
                      onClick={() => searchFor(searched)}
                      title="重新搜索当前关键词"
                      aria-label="重新搜索当前关键词"
                    >
                      <RefreshCw
                        size={16}
                        className={searchLoading ? "spin" : ""}
                      />
                      刷新
                    </button>
                  </div>
                )}
                <div className="section-heading">
                  <div>
                    <span className="section-kicker">SEARCH RESULTS</span>
                    <h2>
                      “{searched}” 的搜索结果{" "}
                      <span>
                        {searchLoading
                          ? `已找到 ${results.length} 条 · 搜索中...`
                          : `共 ${results.length} 条`}
                      </span>
                    </h2>
                  </div>
                  {window.videoAPI.platform === "android" && (
                    <button
                      className="search-refresh"
                      type="button"
                      onClick={() => searchFor(searched)}
                      title="重新搜索当前关键词"
                      aria-label="重新搜索当前关键词"
                    >
                      <RefreshCw
                        size={16}
                        className={searchLoading ? "spin" : ""}
                      />
                      刷新
                    </button>
                  )}
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
                {searchLoading && results.length === 0 && (
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
                {searchLoading && results.length > 0 && (
                  <div className="search-progress-inline" role="status">
                    <span>继续搜索其他点播源</span>
                    <b>{searchProgress}%</b>
                    <div className="search-progress">
                      <span style={{ width: `${searchProgress}%` }} />
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
                {!searchLoading &&
                  enabled.length > 0 &&
                  results.length === 0 && (
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
                      <div key={entry.key} className="history-card">
                        <button
                          className="history-open"
                          onClick={() => openDetail(entry.item, entry)}
                          aria-label={`继续播放 ${entry.item.title}`}
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
                        <button
                          className="history-delete"
                          title={`删除 ${entry.item.title} 的播放记录`}
                          aria-label={`删除 ${entry.item.title} 的播放记录`}
                          onClick={() => {
                            const next = history.filter(
                              (item) => item.key !== entry.key,
                            );
                            setHistory(next);
                            saveStorage("history", next);
                          }}
                        >
                          <X size={16} />
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </section>
            )}
          </main>
        </div>
      </div>

      {selected && (
        <div className={`detail-overlay${detailClosing ? " is-closing" : ""}`}>
          {window.videoAPI.platform !== "android" && (
            <div className="detail-toolbar">
              <button
                onClick={() => {
                  closeDetail();
                }}
              >
                <ArrowLeft size={19} /> 返回列表
              </button>
              <span>影片详情</span>
            </div>
          )}
          <div className="detail-scroll" ref={detailScroll}>
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
                <div
                  className={`detail-content${episode === null ? " detail-content-intro" : ""}`}
                >
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
                            {selected.rating && (
                              <span>★ {selected.rating}</span>
                            )}
                          </div>
                        </div>
                        <p>
                          {detail.description || "暂无剧情介绍"}
                        </p>
                        {detail.actors && (
                          <small className="detail-hero-actors">
                            主演：{detail.actors}
                          </small>
                        )}
                        <button
                          className="primary-button"
                          data-tv-autofocus
                          onClick={() => {
                            failedLines.current.clear();
                            fallbackPosition.current = null;
                            // 从搜索结果重进详情时，也要按这部影片的记录选中集数和线路。
                            const resumeLine = selectedHistory?.line ?? 0;
                            const resumeEpisode = selectedHistory?.episode ?? 0;
                            if (detail.lines[resumeLine]?.episodes[resumeEpisode]) {
                              setLine(resumeLine);
                              setEpisode(resumeEpisode);
                            } else {
                              setLine(0);
                              setEpisode(0);
                            }
                            detailScroll.current?.scrollTo(0, 0);
                          }}
                          disabled={!playing}
                        >
                          <Play size={17} fill="currentColor" />{" "}
                          {selectedHistory ? "继续播放" : "立即播放"}
                        </button>
                      </div>
                    ) : (
                      playing && (
                        <>
                          {casting ? (
                            <div
                              className="video-shell casting-screen"
                              role="status"
                            >
                              <MonitorPlay size={48} />
                              <strong>正在投屏到「{casting}」</strong>
                              <span>本机播放已停止</span>
                              <button onClick={stopCasting} disabled={castBusy}>
                                {castBusy
                                  ? "正在停止投屏..."
                                  : "停止投屏并返回本机播放"}
                              </button>
                              {castError && <small>{castError}</small>}
                            </div>
                          ) : (
                            <Player
                              key={`${selected.sourceUrl}-${selected.id}`}
                              url={playing.url}
                              filterAds={filterAds}
                              playbackSerial={playbackSerial}
                              title={detail.title}
                              episodeLabel={playing.label}
                              hasNextEpisode={
                                episode + 1 < detail.lines[line].episodes.length
                              }
                              resume={
                                fallbackPosition.current ??
                                (selectedHistory?.line === line &&
                                selectedHistory.episode === episode
                                  ? selectedHistory.position
                                  : 0)
                              }
                              onProgress={recordProgress}
                              onEnded={nextEpisode}
                              onNextEpisode={playNextEpisode}
                              onFailure={(position) => {
                                if (!detail || episode === null) return false;
                                failedLines.current.add(line);
                                const currentEpisode =
                                  detail.lines[line].episodes[episode];
                                // 优先保持集名一致；线路命名不同但集数一致时使用相同序号。
                                for (
                                  let offset = 1;
                                  offset < detail.lines.length;
                                  offset++
                                ) {
                                  const nextLine =
                                    (line + offset) % detail.lines.length;
                                  if (failedLines.current.has(nextLine))
                                    continue;
                                  const episodes =
                                    detail.lines[nextLine].episodes;
                                  let nextEpisode = episodes.findIndex(
                                    (item) =>
                                      item.label === currentEpisode.label &&
                                      item.url,
                                  );
                                  if (nextEpisode < 0 && episodes[episode]?.url)
                                    nextEpisode = episode;
                                  if (nextEpisode < 0) continue;
                                  fallbackPosition.current = position;
                                  setLine(nextLine);
                                  setEpisode(nextEpisode);
                                  return true;
                                }
                                return false;
                              }}
                              playerInstance={playerInstance}
                              tvFullscreen={tvPlayerFullscreen}
                            />
                          )}
                          <div className="player-title">
                            <div>
                              <span>{casting ? "正在投屏" : "正在播放"}</span>
                              <h2>
                                {detail.title} <small>· {playing.label}</small>
                              </h2>
                            </div>
                            {!casting && (
                              <div className="player-actions">
                                {isTv && (
                                  <button
                                    onClick={() => setTvPlayerFullscreen(true)}
                                  >
                                    <Maximize2 size={17} /> 全屏播放
                                  </button>
                                )}
                                {!isTv && (
                                  <button onClick={scanDevices}>
                                    <MonitorPlay size={17} /> 投屏
                                  </button>
                                )}
                                {episode + 1 <
                                  detail.lines[line].episodes.length && (
                                  <button
                                    onClick={playNextEpisode}
                                  >
                                    <SkipForward size={17} /> 下一集
                                  </button>
                                )}
                              </div>
                            )}
                          </div>
                          {castError && !castOpen && !casting && (
                            <div className="notice error">{castError}</div>
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
                            </div>
                            <p className="player-details-description">
                              {detail.description || "暂无剧情介绍"}
                            </p>
                            {detail.actors && (
                              <p className="player-details-actors">
                                主演：{detail.actors}
                              </p>
                            )}
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
                              disabled={!!casting}
                              onClick={() => {
                                failedLines.current.clear();
                                fallbackPosition.current = null;
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
                          <div
                            className="episode-order-control"
                            role="group"
                            aria-label="集数顺序"
                          >
                            <strong>
                              共 {detail.lines[line].episodes.length} 集
                            </strong>
                            <button
                              type="button"
                              className={
                                episodeOrder === "ascending" ? "active" : ""
                              }
                              aria-pressed={episodeOrder === "ascending"}
                              onClick={() => setEpisodeOrder("ascending")}
                            >
                              正序
                            </button>
                            <button
                              type="button"
                              className={
                                episodeOrder === "descending" ? "active" : ""
                              }
                              aria-pressed={episodeOrder === "descending"}
                              onClick={() => setEpisodeOrder("descending")}
                            >
                              倒序
                            </button>
                          </div>
                        </div>
                        <div
                          className={
                            hasLongEpisodeLabels
                              ? "episode-list episode-list-long"
                              : "episode-list"
                          }
                        >
                          {displayedEpisodes.map(({ item, index }) => (
                            <button
                              key={`${item.url}-${index}`}
                              className={episode === index ? "active" : ""}
                              disabled={castBusy}
                              onClick={() => void selectEpisode(index)}
                              title={item.label}
                            >
                              {item.label}
                            </button>
                          ))}
                        </div>
                        <div className="panel-tip">
                          自动连播{autoplay ? "已开启" : "已关闭"}
                        </div>
                      </>
                    )}
                  </div>
                </div>
              )
            )}
          </div>
        </div>
      )}

      {settingsOpen && (
        <div
          className={`drawer-backdrop${settingsClosing ? " is-closing" : ""}`}
          onMouseDown={(event) => {
            if (event.target === event.currentTarget) closeSettings();
          }}
        >
          <div className="settings-drawer">
            <div className="drawer-header">
              <div>
                <span>偏好设置</span>
                <h2>让观影更顺手</h2>
              </div>
              <small className="settings-version">v{version}</small>
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
              <label className="source-concurrency">
                <span>每批并发测试</span>
                <input
                  type="number"
                  min={1}
                  max={100}
                  step={1}
                  value={testBatchSize}
                  disabled={testing}
                  onChange={(event) => {
                    const value = Number(event.target.value);
                    if (!Number.isInteger(value) || value < 1 || value > 100)
                      return;
                    setTestBatchSize(value);
                    saveStorage("source-test-concurrency", value);
                  }}
                  aria-label="每批并发测试的点播源数量"
                />
                <small>个源（1–100）</small>
              </label>
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
                      : `每批并发测试 ${testBatchSize} 个源，完成后自动更新勾选状态。`}
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
              <button
                type="button"
                className="source-link"
                onClick={() => setSourceFormatOpen(true)}
              >
                了解点播源格式 <ChevronDown size={14} />
              </button>
            </div>
          </div>
        </div>
      )}

      {sourceFormatOpen && (
        <div
          className="modal-backdrop source-format-backdrop"
          onMouseDown={(event) => {
            if (event.target === event.currentTarget)
              setSourceFormatOpen(false);
          }}
        >
          <div
            className="cast-modal source-format-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="source-format-title"
          >
            <div className="modal-heading">
              <div>
                <Settings2 size={22} />
                <h2 id="source-format-title">点播源格式示例</h2>
              </div>
              <button
                className="icon-button"
                aria-label="关闭点播源格式示例"
                onClick={() => setSourceFormatOpen(false)}
              >
                <X size={20} />
              </button>
            </div>
            <p>
              订阅地址应返回 JSON。每个点播源需要名称和 Apple CMS 接口地址；
              <code>isAdult</code> 可省略，默认为非成人源。
            </p>
            <pre>{`{
  "sources": [
    {
      "name": "示例点播源",
      "url": "https://example.com/api.php/provide/vod/",
      "isAdult": false
    }
  ]
}`}</pre>
            <p>
              这里的 example.com 仅作格式示例。将自己的 JSON 文件放到可访问的
              HTTP 或 HTTPS 地址，再把文件地址粘贴到上方的订阅输入框。
            </p>
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
              确保{window.videoAPI.platform === "android" ? "手机" : "电脑"}
              和电视在同一局域网。
              {window.videoAPI.platform === "android"
                ? "支持 DLNA 视频接收设备。"
                : "支持 DLNA 和 AirPlay 视频接收设备。"}
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
                <p>
                  检查电视的投屏功能和网络连接。
                  {window.videoAPI.platform === "darwin" &&
                    "还需在 macOS「系统设置 → 隐私与安全性 → 本地网络」中允许影窗访问。"}
                </p>
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
            {!castBusy && !pairingDevice && (
              <button className="load-more" onClick={scanDevices}>
                <RefreshCw size={15} /> 重新搜索
              </button>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
