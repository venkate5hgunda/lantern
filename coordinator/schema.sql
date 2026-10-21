-- Coordinator schema v1. Contains setup/signaling metadata only, never media.
CREATE TABLE devices (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  token_hash TEXT NOT NULL,
  claim_hash TEXT UNIQUE,
  claim_expires INTEGER NOT NULL,
  owner_id TEXT,
  created INTEGER NOT NULL,
  last_seen INTEGER
);
CREATE INDEX devices_owner ON devices(owner_id);
CREATE TABLE signals (
  id TEXT PRIMARY KEY,
  device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
  owner_id TEXT NOT NULL,
  method TEXT NOT NULL,
  path TEXT NOT NULL,
  body TEXT NOT NULL,
  created INTEGER NOT NULL,
  expires INTEGER NOT NULL,
  result TEXT
);
CREATE INDEX signals_pending ON signals(device_id, expires);
CREATE TABLE rate_limits (key TEXT PRIMARY KEY, count INTEGER NOT NULL, expires INTEGER NOT NULL);
