# Archive Cleaner (Android)

A small, deliberately boring tool: point it at an Instagram export `.zip`, let it
find files on this phone whose **contents are byte-for-byte identical** to media
inside that archive, review the list, and delete the ones you tick.

It is a separate app from the IG viewer on purpose. The viewer is read-only;
this one deletes. Keeping them apart means deleting power is never one wrong
tap away from the browsing UI, and the viewer's permissions stay as narrow as
they were. (The viewer lives in a different repository.)

## What is a "copy"

Only a **SHA-256 match** counts. Two files match when their size and hash are
equal, and the hash is compared after the size prefilter agrees. A photo that was
re-saved from Instagram, re-compressed, resized or cropped is a *different file*
and will not be offered — that is intentional. A hash match cannot be a false
positive; a filename match can.

The archive is hashed the moment you pick it: entries are streamed out of the
zip and hashed, nothing is extracted and nothing is written. The zip itself is
never modified.

**Device files are never filtered by name or extension.** A copy whose own name
lost its extension (`DCIM/Camera/384312553936495`) is found like any other; only
its size and bytes matter. On the archive side, an entry with no extension at all
is classified by its leading bytes (JPEG, PNG, GIF, WebP, HEIC, MP4, WAV, …),
because Instagram sometimes stores media under a bare timestamp — the viewer app
documents the same case.

## What is covered

Archive entries are indexed when their name ends in a recognised extension:

| Kind | Extensions |
|---|---|
| Images (incl. GIF, stickers) | `jpg jpeg png gif webp heic heif bmp tif tiff dng` |
| Videos (incl. reels) | `mp4 mov m4v 3gp mkv webm avi mpg mpeg` |
| Audio / voice notes | `mp3 m4a aac wav ogg opus flac amr oga` |

…plus extensionless entries whose first bytes sniff as one of those. Everything
else in the export (`message_1.json`, HTML, profile pictures' JSON references,
etc.) is ignored: it is not media and would only add noise to a delete list.

The list above decides what is read **out of the archive**. It does not restrict
the device: any file whose size and SHA-256 match an indexed entry is shown,
whatever its own name, extension or kind.

## Safety model (each rule is enforced in code and covered by a test)

1. **No automatic deletion.** A scan only produces candidates; the delete button
   stays disabled until files are ticked, and a dialog asks again with the count
   and total size.
2. **Re-verify immediately before deleting.** The file's size and SHA-256 are
   recomputed at delete time. A file that changed since the scan is reported as
   "changed since the scan — kept", not deleted.
3. **Containment.** A delete is refused unless the file's *real* parent directory
   is inside the real shared-storage root. A directory swapped for a symlink
   after the scan cannot redirect a delete outside.
4. **No symlinks.** Symlinked files and directories are skipped during the scan
   and refused again at delete time.
5. **Skipped areas.** Hidden names (`.nomedia`, `.thumbnails`, …), the whole
   `Android/` tree (other apps' data and this app's own cache), directories that
   cannot be listed, and empty files.
6. **Archives are never deleted.** `.zip`, `.7z`, `.rar`, `.tar`, `.gz`, `.xz`,
   `.iso`, `.apk`, … are refused by name in the scanner *and* refused again in
   `Deletions.deleteVerified`. A zip can never be one of the export's media
   files, so it is not hashed, not shown, and not deletable — even if its bytes
   happen to equal an archived photo. The picked export zip is one of these.
7. **Per-file outcomes.** The result dialog reports deleted / already gone /
   changed / refused / could not delete. Nothing is silently swallowed.
8. **No network.** There is no `INTERNET` permission and no code path that
   connects to anything.

## What is scanned

Every mounted shared-storage volume, not just internal storage:

- `/storage/emulated/0` (internal storage), and
- any SD card, discovered through the app's own per-volume directory
  (`/storage/XXXX-XXXX/Android/data/com.igviewer.cleaner/files`) and reduced to
  its volume root. Scanning starts at the card's top level; the `Android/` rule
  then skips the app directories on each volume.

So an export extracted onto the SD card months ago is covered. A file's
candidate record stores the volume root it was found under, and deletion
re-checks containment against *that* root, so a candidate cannot be redirected
to another volume.

## Permissions

| Permission | Why |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | The whole point: find copies anywhere on shared storage, and delete them. Granted by hand in system Settings (Android 11+), never silently. |
| `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` (`maxSdkVersion 29`) | The same capability on Android 10 and below, requested at runtime. |

On Android 10 the manifest also sets `requestLegacyExternalStorage="true"`:
scoped storage would otherwise let the scan *find* a copy but refuse its
deletion even with `WRITE_EXTERNAL_STORAGE`. Android 11+ ignores that attribute
and relies on all-files access instead.

Nothing else is declared. `Android/` is skipped even though all-files access
would technically allow it: it is other apps' private storage, not the user's
photo library.

## Limits (known, intentional)

- Only media referenced *inside the zip* is searched for. Loose files that
  happen to look like exports are irrelevant; the archive is the reference.
- Re-encoded copies are invisible by design (see above).
- An archive entry with an *unfamiliar* extension (say `.avif`) is not indexed;
  Instagram does not produce those. Extensionless entries are sniffed, so the
  bare-timestamp case is covered.
- The export `.zip` is read through a SAF stream. A zip that cannot be read that
  way but can be read by central-directory lookup is only handled when it is a
  real file path; the picker path requires a well-formed stream. In practice
  every zip produced by Instagram's "Download your information" works.
- An SD card is found through `getExternalFilesDirs`. A card mounted in a way
  Android does not expose that way (or an adopted/merged card, which reports as
  internal storage) will not get its own root.
- Only files are deleted, never directories. The old export's `message_*.json`,
  HTML and now-empty folders are left for you to remove by hand.
- No device test has been run yet on this build machine (no phone/emulator
  attached). JVM unit tests and a release build are the current verification.

## Build and test

```bash
export ANDROID_HOME=$HOME/Android/Sdk ANDROID_SDK_ROOT=$HOME/Android/Sdk
./gradlew :app:testDebugUnitTest     # JVM tests, no device needed
./gradlew :app:assembleRelease       # R8 build, signed with the debug key
```

Run those from the repository root. The release APK lands in
`app/build/outputs/apk/release/app-release.apk`.

`local.properties` (SDK path) and every `build/`/`.gradle/` directory are
gitignored. Tests only ever use generated temporary files — never a real export.

## Code map

| File | Role |
|---|---|
| `data/MediaKind.kt` | Extension → media kind. Never decides a match. |
| `data/Sniff.kt` | Leading-bytes classification for extensionless archive media. |
| `data/Hashing.kt` | Streaming SHA-256 with a size cap. |
| `data/ArchiveZip.kt` | Reads a zip stream, hashes media entries, enforces caps. |
| `data/ArchiveIndex.kt` | Size → hash index; `matches()` requires both. |
| `data/DeviceScan.kt` | Size-prefiltered walk of one volume; `scanAll` covers all volumes; archive names are skipped. |
| `data/StorageRoots.kt` | Internal storage + mounted SD cards, as scan roots. |
| `data/Deletions.kt` | Re-hash + containment, then delete one file. Refuses archives and symlinks. |
| `data/Format.kt` | Byte counts and root-relative paths for the UI. |
| `MainActivity.kt` | The single Compose screen and its state machine. |
