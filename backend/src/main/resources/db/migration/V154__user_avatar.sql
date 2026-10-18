-- Self-service profile photo. Stored inline as a small, client-resized data URL
-- (data:image/...;base64,...) on the user row; the app layer caps the size and
-- validates the image type. NULL = no photo (the UI shows the user's initials).
ALTER TABLE users ADD COLUMN avatar TEXT;
