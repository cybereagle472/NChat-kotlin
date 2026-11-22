-- Apply before deploying the provenance-aware Worker. Historical references are
-- deliberately NOT backfilled: before authorization was enforced, any user
-- could record somebody else's URL. A new reference is recorded only alongside
-- an authorized write, in the same D1 batch.
CREATE TABLE IF NOT EXISTS trusted_media_references (
  file_path TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('message', 'group_message', 'status', 'user_avatar', 'group_avatar')),
  reference_id TEXT NOT NULL,
  actor_id TEXT NOT NULL,
  PRIMARY KEY (file_path, kind, reference_id)
);
CREATE INDEX IF NOT EXISTS idx_trusted_media_reference ON trusted_media_references(kind, reference_id);
CREATE TRIGGER IF NOT EXISTS cleanup_message_media_refs AFTER DELETE ON messages BEGIN
  DELETE FROM trusted_media_references WHERE kind = 'message' AND reference_id = OLD.id;
END;
CREATE TRIGGER IF NOT EXISTS cleanup_group_message_media_refs AFTER DELETE ON group_messages BEGIN
  DELETE FROM trusted_media_references WHERE kind = 'group_message' AND reference_id = OLD.id;
END;
CREATE TRIGGER IF NOT EXISTS cleanup_status_media_refs AFTER DELETE ON statuses BEGIN
  DELETE FROM trusted_media_references WHERE kind = 'status' AND reference_id = OLD.id;
END;
CREATE TRIGGER IF NOT EXISTS cleanup_user_avatar_media_refs AFTER DELETE ON users BEGIN
  DELETE FROM trusted_media_references WHERE kind = 'user_avatar' AND reference_id = OLD.id;
END;
CREATE TRIGGER IF NOT EXISTS cleanup_group_avatar_media_refs AFTER DELETE ON groups BEGIN
  DELETE FROM trusted_media_references WHERE kind = 'group_avatar' AND reference_id = OLD.id;
END;
CREATE TRIGGER IF NOT EXISTS cleanup_attachment_media_refs AFTER DELETE ON media_attachments BEGIN
  DELETE FROM trusted_media_references WHERE file_path = OLD.file_path;
END;
