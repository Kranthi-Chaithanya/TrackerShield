# App signing key

`trackershield-release.p12` is the permanent key that signs TrackerShield, so that each new
version installs over the previous one and keeps your history.

The file is encrypted (AES-256, PBKDF2) with a long random password and is useless without it.
The password is stored only in the repository secret `SIGNING_PASSWORD`
(Settings > Secrets and variables > Actions). Never put the password in this repository.

If `SIGNING_PASSWORD` is missing, the GitHub build falls back to a temporary test key.
