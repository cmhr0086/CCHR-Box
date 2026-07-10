CREATE TABLE IF NOT EXISTS invite_subscriptions (
  invite_code TEXT PRIMARY KEY,
  subscription_url TEXT NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1)),
  note TEXT,
  created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TRIGGER IF NOT EXISTS invite_subscriptions_set_updated_at
AFTER UPDATE ON invite_subscriptions
FOR EACH ROW
WHEN NEW.updated_at = OLD.updated_at
BEGIN
  UPDATE invite_subscriptions
  SET updated_at = CURRENT_TIMESTAMP
  WHERE invite_code = OLD.invite_code;
END;
