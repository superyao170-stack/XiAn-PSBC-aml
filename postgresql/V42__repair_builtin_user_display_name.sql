-- Repair the one built-in account nickname that was historically inserted
-- through a lossy non-UTF-8 path.
UPDATE sys_user
SET nickname = '超级管理员'
WHERE username = 'sadmin'
  AND (nickname ~ '^[?]+$' OR position(chr(65533) in nickname) > 0);

ALTER TABLE sys_user
    ADD CONSTRAINT chk_sys_user_nickname_encoding
    CHECK (nickname !~ '[?]{2,}' AND position(chr(65533) in nickname) = 0);
