-- 0007: restore friendships that an accepted friend request still declares
-- but a destructive block deleted. /friends/block has always removed the
-- friendships row while leaving the friend_requests row 'accepted', and
-- /friends/unblock restores neither; before Phase 1's send-time friendship
-- check those pairs could still message, afterwards they could neither
-- message (403) nor re-friend (409 "Already friends").
--
-- Pairs with a block in either direction are skipped: they stay unfriended
-- on purpose, and the request-time repair in worker/src/routes/friends.ts
-- reconnects them after an unblock.
--
-- Run manually by the owner (code deployment does not apply migrations):
--   npx wrangler d1 execute nchat-db --remote --file=./migrations/0007_restore_friendships.sql
INSERT INTO friendships (id, user1_id, user2_id)
SELECT lower(hex(randomblob(4))) || '-' || lower(hex(randomblob(2))) || '-4' || lower(hex(randomblob(2))) || '-a' || lower(hex(randomblob(2))) || '-' || lower(hex(randomblob(6))),
       fr.sender_id, fr.receiver_id
FROM friend_requests fr
WHERE fr.status = 'accepted'
  AND NOT EXISTS (
    SELECT 1 FROM friendships f
    WHERE (f.user1_id = fr.sender_id AND f.user2_id = fr.receiver_id)
       OR (f.user1_id = fr.receiver_id AND f.user2_id = fr.sender_id)
  )
  AND NOT EXISTS (
    SELECT 1 FROM blocked_users b
    WHERE (b.user_id = fr.sender_id AND b.blocked_user_id = fr.receiver_id)
       OR (b.user_id = fr.receiver_id AND b.blocked_user_id = fr.sender_id)
  )
  AND EXISTS (SELECT 1 FROM users u WHERE u.id = fr.sender_id)
  AND EXISTS (SELECT 1 FROM users u WHERE u.id = fr.receiver_id);
