-- Built-in account passwords follow the username@123 convention.
-- Passwords are stored only as BCrypt hashes and are never exposed by login or user APIs.
UPDATE sys_user SET password='$2b$10$GECjB.l2XWETvBIv9tdEYec5z8anNfphxPlech/hRe7p86UDoDk4a'
WHERE username='sadmin';
UPDATE sys_user SET password='$2b$10$l0sSUs0avvcYKxGvf7wRPuU4XA/TLuNIHJEVToVcxXo8AdXoJm6Ra'
WHERE username='badmin';
UPDATE sys_user SET password='$2b$10$KSzoK/ZNnig3o6Rg8vkygu9FGqOhLnXTqZ6i7EfJHP0j/Iu6A7K7K'
WHERE username='madmin';
