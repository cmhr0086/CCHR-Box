CREATE TABLE IF NOT EXISTS app_announcements (
  id TEXT PRIMARY KEY,
  title TEXT,
  content TEXT NOT NULL DEFAULT '',
  enabled INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER IF NOT EXISTS app_announcements_set_updated_at
AFTER UPDATE ON app_announcements
FOR EACH ROW
BEGIN
  UPDATE app_announcements
  SET updated_at = CURRENT_TIMESTAMP
  WHERE id = OLD.id;
END;
