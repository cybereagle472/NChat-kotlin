-- E2EE attachments (Batch 5): the audience of a sealed file.
--
-- A photo, video, voice message or file of an encrypted conversation is
-- sealed on the phone before it is uploaded; the key travels inside the
-- sealed message. The server cannot read the message, so it cannot tell from
-- it who may fetch the file, the way it does for readable messages. The
-- uploader says so at upload: one person, or one group. Exactly one of the
-- two columns is set for a sealed file; both are NULL for every other file,
-- and those keep following the messages that name them.
--
-- Additive: two nullable columns, no existing row changes.

ALTER TABLE media_attachments ADD COLUMN sealed_for_user TEXT;
ALTER TABLE media_attachments ADD COLUMN sealed_for_group TEXT;

-- E2EE groups (Batch 4): who was handed which chain.
--
-- A member seals what they write to a group with a chain of their own and
-- hands that chain to each other member inside a sealed one-to-one envelope.
-- One row is one such envelope: the server cannot open it, it keeps it for
-- the member it was sealed for. `recipient_identity_key` is the identity key
-- that envelope was sealed for (public material): it is how the server can
-- tell that a member has registered other keys since, and that the phone
-- they had still holds the chain.
--
-- Rows are not removed when a member leaves: a chain that somebody outside
-- the group still holds is exactly what a sender has to be told about.

CREATE TABLE group_sender_keys (
  group_id TEXT NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  sender_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  chain_id TEXT NOT NULL,
  recipient_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recipient_identity_key TEXT NOT NULL,
  envelope TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  PRIMARY KEY (group_id, sender_id, chain_id, recipient_id)
);

CREATE INDEX idx_group_sender_keys_by_recipient ON group_sender_keys(group_id, recipient_id);
