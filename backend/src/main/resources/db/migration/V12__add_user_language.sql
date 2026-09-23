-- WIKI4AI-73: per-user UI language preference (WebUI internationalization).
-- The WebUI ships two locales (en, sk); the profile page persists the user's
-- choice here. Default 'en' so every existing account sees English until it
-- explicitly picks otherwise (new users and legacy rows alike).
ALTER TABLE users ADD COLUMN language VARCHAR(5) NOT NULL DEFAULT 'en';
