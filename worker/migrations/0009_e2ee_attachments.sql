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
