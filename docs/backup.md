# Encrypted backups (`.ourabak`)

The ring keeps about eight and a half days. Everything older exists only in the database of
whichever device synced it, so a lost phone used to be a lost history. A backup is one
encrypted file that either client can write and either can restore.

| Client | Where |
| --- | --- |
| Desktop | `oura --db oura.db backup <file>` and `oura --db oura.db restore <file>` |
| Android | You tab → Ring & data → **Backup** |
| Web dashboard, iOS | no UI — use the desktop command |

The code is `crates/oura-backup`; its module comment is the reference for the byte layout.

## What is in one

A *logical* copy, not the SQLite file:

- every event as the ring delivered it: serial, tag, ring timestamp, body, capture time
- the device rows, the sync cursors and the scalar readings
- `profile.json` and `feature_modes.json`, which live beside the database

Decoded JSON and indexes are left out because they are derived. Restoring re-decodes each
body with the decoders of the build doing the restoring, as `oura redecode` would. That is
why a backup is a fraction of the database — 80 MB for a 1.03 GB database of 5.66 million
events — and why it does not depend on the schema.

**The ring's auth key is never included.** A backup is meant to be put somewhere less
trusted than the phone, and the key is what lets someone talk to the ring. Keep it
separately.

## Restoring is merging

Events go in through the store's own rule, `UNIQUE(serial, tag, ring_timestamp, body)` with
`INSERT OR IGNORE`, so only what is missing is added. The same file can be restored twice,
into a new database or one that has synced on its own since. For the other tables:

- a device row is replaced only by one with a newer `updated_unix`
- a sync cursor only moves forward (later `last_sync_unix` wins)
- a reading is skipped if the same serial, kind, value and capture time is already there
- a side file is written only if the destination has none

The whole restore is one transaction. A wrong passphrase or a damaged file changes nothing,
and does not create a database that was not there.

## The file

Everything little-endian.

```text
header   "OURABAK\x01" | argon2id m_kib u32 | t u32 | p u32 | salt[16] | nonce[19]   (55 bytes)
chunks   ( last u8 | len u32 | ciphertext[len] )*
```

- **Key**: Argon2id over the passphrase and salt, 64 MiB / 3 passes / 1 lane by default.
  The parameters are in the header so they can be raised later without orphaning old
  files. A reader refuses parameters outside sane bounds, since the header is unauthenticated
  until the first chunk opens.
- **Chunks**: XChaCha20-Poly1305 in the STREAM construction (32-bit big-endian counter),
  each sealing up to 64 KiB, with the header as associated data. STREAM binds every chunk to
  its position and marks the last one, so a file that was cut short, reordered or altered
  fails to open instead of restoring part of itself.
- **Plaintext**: a deflate stream of `kind u8 | len u32 | payload` records — `1` manifest
  (JSON, first), `2` device, `3` sync cursor, `4` reading, `5` event (binary), `6` side
  file, `9` end (carries the event count, last).

Nothing seeks, in either direction. That matters on Android, where a cloud document
provider hands the app a pipe rather than a file.

A reader rejects a format number higher than its own (`newer_format`) rather than guessing.

## The passphrase

At least 8 characters, asked for every time and never stored — not in the app's
preferences, not in the Android Keystore. There is no recovery: a forgotten passphrase is a
lost backup. The desktop command prompts on the terminal (twice when writing) or reads
`OURA_BACKUP_PASSPHRASE`, for scripts.

"Wrong passphrase" and "damaged file" cannot always be told apart — both are a first chunk
that fails to authenticate — so the message names both.

## Android

The destination and the source are chosen with the system file picker
(`CreateDocument` / `OpenDocument`), and the file descriptor is handed to Rust
(`backup_to_fd` / `restore_from_fd` in `oura-core`). Saving into Google Drive, or anywhere
else with a document provider, therefore works through that provider's own app; **open_oura
still has no `INTERNET` permission**. Restore is refused while a sync is running, and the
summary is recomputed afterwards if anything was added.

Measured on a Pixel 9a, release build: 5,655,007 events backed up in 23 s; the same file
restored into an empty database on a laptop in 23 s.

## Automatic backup (Android)

Off by default. Switched on from the same screen, it writes a backup once a day to **one
file chosen once**, with nobody there to type a passphrase. Code:
`apps/android/.../sync/AutoBackup.kt`.

- **Where.** The system file picker again (`CreateDocument`), and the app keeps the grant
  with `takePersistableUriPermission`. It is one document, not a folder: Google Drive does
  not offer folders to `ACTION_OPEN_DOCUMENT_TREE`. So each run replaces
  `open_oura-auto.ourabak`, and each backup is complete. The provider's own version
  history, where it has one, is the only history.
- **The provider uploads, not the app.** There is still no `INTERNET` permission. "Backed
  up" on the screen means the file was handed to the provider; whether and when it reached
  the cloud is the provider's business and the app cannot see it.
- **When.** A 24-hour WorkManager job that needs charging and an unmetered network, so the
  provider's upload most likely follows on Wi-Fi. It is skipped when neither `oura.db` nor
  its WAL has been modified since the last backup began — a file `stat`, because the
  alternative is opening a gigabyte database every night to count rows. Daily is enough:
  the ring holds about eight and a half days, so a phone lost between backups loses nothing
  the ring still has.
- **The passphrase is stored — this is the trade.** It is kept the way the ring key is:
  encrypted by an AES/GCM key that never leaves the Android Keystore, ciphertext in
  SharedPreferences (`BackupSecretStore`). That keeps it from other apps and from an adb
  pull, not from this app's own process on an unlocked, compromised phone. Manual backups
  still ask every time and store nothing. Turning automatic backup off deletes the
  passphrase and its wrapper key and releases the file grant; the backup file stays.
- **A failed run never replaces a good file.** The backup is built in the app's cache and
  only a finished one is copied over the destination. What is not covered: a copy that is
  cut off part-way leaves a truncated file, which `restore` will refuse as damaged rather
  than half-restore. The next day's run rewrites it.
- **Saying when it breaks.** One notification ("Automatic backup isn't working", its own
  switch on the You tab) after three failed days in a row, or at once when only the user
  can fix it: the file grant is gone, or the stored passphrase cannot be read.

The desktop has no scheduler of its own: `OURA_BACKUP_PASSPHRASE=… oura backup <file>`
from cron does the same job.

## Not done yet

- **Incremental backups.** Every backup is complete. One document is all the picker grants
  in Drive, so increments would need the Drive API, an account sign-in and the `INTERNET`
  permission — a deliberate non-goal so far.
