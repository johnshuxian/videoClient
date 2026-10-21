import { common } from '@kit.AbilityKit';
import { relationalStore } from '@kit.ArkData';

export interface HistoryRow {
  key: string;
  itemJson: string;
  detailJson: string;
  line: number;
  episode: number;
  position: number;
  duration: number;
  updatedAt: number;
}

export interface SettingRow {
  key: string;
  value: string;
}

interface HistoryIdentity { title: string; sourceUrl: string }

export class SettingsStore {
  private database: relationalStore.RdbStore;

  private constructor(database: relationalStore.RdbStore) {
    this.database = database;
  }

  static async open(context: common.Context): Promise<SettingsStore> {
    const database = await relationalStore.getRdbStore(context, {
      name: 'settings.db',
      securityLevel: relationalStore.SecurityLevel.S1
    });
    await database.executeSql(`CREATE TABLE IF NOT EXISTS play_history (
      key TEXT PRIMARY KEY,
      item_json TEXT NOT NULL,
      detail_json TEXT NOT NULL,
      title TEXT NOT NULL DEFAULT '',
      source_url TEXT NOT NULL DEFAULT '',
      line INTEGER NOT NULL,
      episode INTEGER NOT NULL,
      position REAL NOT NULL,
      duration REAL NOT NULL,
      updated_at INTEGER NOT NULL
    )`);
    await database.executeSql('CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL)');
    await database.executeSql('CREATE TABLE IF NOT EXISTS detail_cache (key TEXT PRIMARY KEY, detail_json TEXT NOT NULL)');
    const columns = await database.querySql('PRAGMA table_info(play_history)');
    let hasTitle = false;
    let hasSourceUrl = false;
    try {
      while (columns.goToNextRow()) {
        if (columns.getString(1) === 'title') hasTitle = true;
        if (columns.getString(1) === 'source_url') hasSourceUrl = true;
      }
    } finally {
      columns.close();
    }
    if (!hasTitle) await database.executeSql("ALTER TABLE play_history ADD COLUMN title TEXT NOT NULL DEFAULT ''");
    if (!hasSourceUrl) await database.executeSql("ALTER TABLE play_history ADD COLUMN source_url TEXT NOT NULL DEFAULT ''");
    // 旧记录从 item_json 回填，查询无需依赖数据库的 JSON 函数。
    const missing = await database.querySql("SELECT key, item_json FROM play_history WHERE title = '' OR source_url = ''");
    const oldRows: string[][] = [];
    try {
      while (missing.goToNextRow()) oldRows.push([missing.getString(0), missing.getString(1)]);
    } finally {
      missing.close();
    }
    for (const row of oldRows) {
      const item = JSON.parse(row[1]) as HistoryIdentity;
      await database.executeSql('UPDATE play_history SET title = ?, source_url = ? WHERE key = ?',
        [item.title || '', item.sourceUrl || '', row[0]]);
    }
    await database.executeSql('CREATE INDEX IF NOT EXISTS play_history_title_updated ON play_history (title, updated_at DESC)');
    // 旧列保留给已安装版本；迁移后详情只从 detail_cache 读取。
    await database.executeSql(`INSERT OR IGNORE INTO detail_cache (key, detail_json)
      SELECT key, detail_json FROM play_history WHERE detail_json <> ''`);
    await database.executeSql("UPDATE play_history SET detail_json = '' WHERE detail_json <> ''");
    return new SettingsStore(database);
  }

  async listSettings(): Promise<SettingRow[]> {
    const result = await this.database.querySql('SELECT key, value FROM settings');
    const rows: SettingRow[] = [];
    try {
      while (result.goToNextRow()) rows.push({ key: result.getString(0), value: result.getString(1) });
    } finally {
      result.close();
    }
    return rows;
  }

  async saveSetting(row: SettingRow): Promise<void> {
    await this.database.executeSql(
      'INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)',
      [row.key, row.value]
    );
  }

  async list(): Promise<HistoryRow[]> {
    const result = await this.database.querySql(
      `SELECT play_history.key, item_json, COALESCE(detail_cache.detail_json, ''), line, episode,
        position, duration, updated_at FROM play_history LEFT JOIN detail_cache USING (key)
        ORDER BY play_history.updated_at DESC`
    );
    const rows: HistoryRow[] = [];
    try {
      while (result.goToNextRow()) {
        rows.push({
          key: result.getString(0),
          itemJson: result.getString(1),
          detailJson: result.getString(2),
          line: result.getLong(3),
          episode: result.getLong(4),
          position: result.getDouble(5),
          duration: result.getDouble(6),
          updatedAt: result.getLong(7)
        });
      }
    } finally {
      result.close();
    }
    return rows;
  }

  async latestOtherSource(title: string, sourceUrl: string): Promise<HistoryRow | null> {
    const result = await this.database.querySql(
      `SELECT play_history.key, item_json, COALESCE(detail_cache.detail_json, ''), line, episode,
        position, duration, updated_at FROM play_history LEFT JOIN detail_cache USING (key)
        WHERE play_history.title = ? AND play_history.source_url <> ?
        ORDER BY play_history.updated_at DESC, play_history.key DESC LIMIT 1`,
      [title, sourceUrl]
    );
    try {
      if (!result.goToNextRow()) return null;
      return {
        key: result.getString(0), itemJson: result.getString(1), detailJson: result.getString(2),
        line: result.getLong(3), episode: result.getLong(4), position: result.getDouble(5),
        duration: result.getDouble(6), updatedAt: result.getLong(7)
      };
    } finally {
      result.close();
    }
  }

  async upsert(row: HistoryRow): Promise<void> {
    const item = JSON.parse(row.itemJson) as HistoryIdentity;
    await this.database.executeSql(`INSERT OR REPLACE INTO play_history
      (key, item_json, detail_json, title, source_url, line, episode, position, duration, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`, [
      row.key, row.itemJson, '', item.title, item.sourceUrl,
      row.line, row.episode,
      row.position, row.duration, row.updatedAt
    ]);
    if (row.detailJson) {
      await this.database.executeSql(`UPDATE detail_cache SET detail_json = ?
        WHERE key = ? AND detail_json <> ?`, [row.detailJson, row.key, row.detailJson]);
      await this.database.executeSql('INSERT OR IGNORE INTO detail_cache (key, detail_json) VALUES (?, ?)',
        [row.key, row.detailJson]);
    }
  }

  async delete(key: string): Promise<void> {
    await this.database.executeSql('DELETE FROM play_history WHERE key = ?', [key]);
    await this.database.executeSql('DELETE FROM detail_cache WHERE key = ?', [key]);
  }

  async clear(): Promise<void> {
    await this.database.executeSql('DELETE FROM play_history');
    await this.database.executeSql('DELETE FROM detail_cache');
  }
}
