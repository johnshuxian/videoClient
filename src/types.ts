export type CatalogItem = {
  id: string;
  title: string;
  poster: string;
  rating: string;
  episodesInfo: string;
};
export type CatalogType = "movie" | "tv" | "anime" | "variety";
export type Source = { name: string; url: string; isAdult: boolean };
export type SourceHealth = {
  ok: boolean;
  latencyMs: number;
  resultCount: number;
  error?: string;
};
export type SearchItem = {
  id: string;
  title: string;
  poster: string;
  year: string;
  remarks: string;
  source: string;
  sourceUrl: string;
  type: string;
  rating: string;
};
export type Episode = { label: string; url: string };
export type VodDetail = {
  title: string;
  poster: string;
  year: string;
  area: string;
  actors: string;
  description: string;
  lines: { name: string; episodes: Episode[] }[];
};
export type Device = {
  id: string;
  name: string;
  host: string;
  type: "dlna" | "airplay";
  paired: boolean;
};
export type HistoryItem = {
  key: string;
  item: SearchItem;
  detail?: VodDetail;
  line: number;
  episode: number;
  position: number;
  duration: number;
  updatedAt: number;
};

declare global {
  interface Window {
    videoAPI: {
      platform: string;
      readSetting: (key: string) => unknown;
      saveSetting: (key: string, value: unknown) => void;
      catalog: (
        type: CatalogType,
        tag: string,
        page: number,
        sort: "rank" | "date" | "heat",
      ) => Promise<CatalogItem[]>;
      importSources: (url: string) => Promise<Source[]>;
      testSource: (source: Source, runId: number) => Promise<SourceHealth>;
      cancelSourceTest: (runId: number) => void;
      imageUrl: (url: string) => Promise<string>;
      search: (
        query: string,
        sources: Source[],
        requestId: number,
        onProgress: (
          completed: number,
          total: number,
          items: SearchItem[],
          failedSource?: string,
        ) => void,
      ) => Promise<{ results: SearchItem[]; failed: string[] }>;
      detail: (sourceUrl: string, id: string) => Promise<VodDetail>;
      streamUrl: (
        url: string,
        filterAds: boolean,
      ) => Promise<{ url: string; isHls: boolean }>;
      devices: () => Promise<{ items: Device[]; warnings: string[] }>;
      airplayPairBegin: (id: string) => Promise<{ pinRequired: boolean }>;
      airplayPairFinish: (pin: string) => Promise<boolean>;
      cast: (
        device: Device,
        url: string,
        title: string,
        filterAds: boolean,
      ) => Promise<boolean>;
      castStop: () => Promise<void>;
      castProgress: () => Promise<{
        deviceId: string;
        url: string;
        position: number;
        duration: number;
      } | null>;
      onCastEnded: (
        callback: (event: { deviceId: string; error: string }) => void,
      ) => () => void;
      setTheme: (theme: "light" | "dark") => void;
      minimize: () => void;
      maximize: () => void;
      close: () => void;
    };
  }
}
