ALTER TABLE rooms
    ADD COLUMN dm_key VARCHAR(50) NULL AFTER avatar_url;

UPDATE rooms r
JOIN (
    SELECT room_id, dm_key
    FROM (
        SELECT pairs.*,
               ROW_NUMBER() OVER (PARTITION BY dm_key ORDER BY created_at ASC, room_id ASC) AS pair_rank
        FROM (
            SELECT r.room_id,
                   r.created_at,
                   CONCAT(
                       LEAST(MIN(rm.user_id), MAX(rm.user_id)),
                       ':',
                       GREATEST(MIN(rm.user_id), MAX(rm.user_id))
                   ) AS dm_key
            FROM rooms r
            JOIN room_members rm ON rm.room_id = r.room_id
            WHERE r.type = 'DM'
            GROUP BY r.room_id, r.created_at
            HAVING COUNT(*) = 2
        ) pairs
    ) ranked
    WHERE pair_rank = 1
) chosen ON chosen.room_id = r.room_id
SET r.dm_key = chosen.dm_key
WHERE r.dm_key IS NULL;

CREATE UNIQUE INDEX uk_rooms_dm_key ON rooms (dm_key);
