# StickerVault

Export a WhatsApp sticker collection off an Android phone into a portable zip,
and restore a chosen part of it into WhatsApp on a new phone.

**Status:** both halves work end to end on real data. Verified against a live
library of 11,203 stickers (1.4 GB).

---

## Why this exists

WhatsApp has no sticker export, and its backup does not reliably carry stickers
across devices. Switch phones and the collection is gone — repeatedly, in the
case that prompted this.

The fix is to stop letting WhatsApp *be* the collection. The archive is a plain
zip of ordinary `.webp` files that opens on any computer, so it outlives the app,
WhatsApp, and the phone. WhatsApp becomes a view onto it.

---

## How it works

**Back up.** Grant the WhatsApp sticker folder once via the system folder picker,
then scan, de-duplicate by content hash, and stream everything into a single zip
in Downloads. Archiving runs in a foreground service so it survives leaving the
app, and notifies on completion.

**Restore.** Point the app at a vault zip. Stickers are extracted into private
storage, regrouped into their original packs (recovered from metadata embedded in
each file), and offered to WhatsApp as sticker packs.

### The constraint worth knowing

WhatsApp does not copy third-party sticker packs into itself — it reads them live
from the providing app, forever. **Uninstall this app and the packs it added
disappear from WhatsApp.** That is how Android WhatsApp works; it has been
[an open request since 2018](https://github.com/WhatsApp/stickers/issues/173),
and every sticker app behaves this way. The release build is ~2 MB, so keeping it
installed costs nothing. The zip is the durable artifact regardless.

WhatsApp also documents a limit of 1–10 packs per app (~300 stickers). Whether it
is enforced at runtime is still unverified.

### Pack recovery

Every WhatsApp sticker carries an EXIF chunk naming its original pack, publisher
and emoji tags. Measured on the reference library: **94.9% carry it**, giving 433
packs of 3+ stickers, plus 783 stickers under "My stickers" — the user's own
in-app creations. This is why grouping survives without root, and why restored
packs keep their real names.

Only 11.3% carry emoji tags, and emoji are WhatsApp's *only* search key for
installed stickers, so most restored stickers get a default tag.

---

## Security

The archive is untrusted input: it comes off the user's storage, may have crossed
a cloud drive and another device, and nothing proves this app produced it.
`VaultImporter` therefore treats it as hostile.

| Threat | Defence |
|---|---|
| Path traversal (Zip Slip) | Entry names must match `^stickers/[0-9a-f]{64}\.webp$` exactly; the written filename is a validated hash and nothing else |
| Filter bypass via control characters | Control characters and over-long names are rejected *before* pattern matching — `$` matches before a trailing newline in most regex flavours |
| Zip bombs | Per-entry, total-byte and entry-count ceilings, plus a free-space check before writing |
| Forged size metadata | `ZipEntry.size` is never trusted; actual bytes read are counted and capped |
| Corrupted or tampered content | Files are content-addressed, so bytes that do not hash to their own filename are rejected — the name *is* the checksum |
| Type confusion | Every entry must parse as a real WebP before being written |
| Hostile metadata | Pack names and emoji come from EXIF in the files; length-capped, and never used to build a path |

The app declares **no storage permission** (the folder picker is a user grant)
and **no `INTERNET` permission** — so regardless of what the code does, the OS
makes it impossible to send the collection anywhere.

```bash
python tools/verify_import_guard.py
```

Throws 34 traversal, encoding and type-confusion payloads at the entry-name rule.
It validates the *rule*, not the Kotlin — keep the two in step by hand.

---

## Building

```bash
export JAVA_HOME="/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot"
export ANDROID_HOME="/c/Users/Shawf/AppData/Local/Android/Sdk"
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # minified, ~2 MB
```

| Piece | Version |
|---|---|
| JDK | Microsoft 17 |
| Android SDK | platform 35, build-tools 35.0.0 |
| Gradle | 8.9 (wrapper) |

`local.properties` points Gradle at the SDK and is gitignored.

The debug build uses applicationId `com.stickervault.debug`, so it installs
alongside a release build rather than replacing it.

### Publishing

Release builds are signed only if `keystore.properties` exists in the project
root. **Create the key yourself** — it must never be generated or stored by
anything but you, and it cannot be replaced once an app is published:

```bash
keytool -genkey -v -keystore upload.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload
```

Then create `keystore.properties` (gitignored):

```properties
storeFile=../upload.jks
storePassword=…
keyAlias=upload
keyPassword=…
```

Store readiness notes:

- No `MANAGE_EXTERNAL_STORAGE`. It was tried, reverted, and must not come back —
  it is unnecessary here and triggers a Play policy review.
- Declared permissions are only `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_DATA_SYNC` and `POST_NOTIFICATIONS`.
- No data leaves the device and none is collected, which makes the Data Safety
  form short — but you still have to fill it in, and Play requires a privacy
  policy URL.
- `lintRelease` passes with 0 errors.

---

## Layout

```
app/src/main/kotlin/com/stickervault/
├── MainActivity.kt              two-tab shell
├── model/StickerEntry.kt        WhatsApp's published limits, in one place
├── vault/
│   ├── WebpProbe.kt             RIFF header parse (dimensions, animation)
│   ├── RiffWebp.kt              embedded EXIF pack metadata
│   ├── StickerScanner.kt        document-tree walker
│   ├── VaultExporter.kt         hash, dedupe, zip
│   ├── VaultImporter.kt         hardened extraction
│   ├── LibraryStore.kt          imported library + grouping
│   ├── PackBuilder.kt           partition, repair, tray icons
│   └── ExportService.kt         foreground service + notifications
├── provider/
│   ├── StickerContentProvider.kt  WhatsApp's cursor contract
│   ├── PackStore.kt               cold-start-safe pack storage
│   └── WhatsAppLink.kt            add-pack intent + whitelist query
└── ui/                          Compose screens
```

### Things that will bite whoever edits this next

- **The ContentProvider is queried cold**, by another process, when the app is
  not running. It must never depend on an Activity, a ViewModel, or anything
  built in `onCreate`. This is the easiest way to ship a provider that works
  while the app is open and fails the moment it is not.
- **Cursor column names are WhatsApp's contract**, not ours. A renamed column
  does not error — WhatsApp silently ignores the pack. `AVOID_CACHE` really is
  `whatsapp_will_not_cache_stickers`.
- **Tray icons must be PNG**, 96×96, ≤50KB. Not WebP.
- **The `<queries>` manifest entries are mandatory.** Without them, Android 11+
  package visibility makes the add-pack intent and whitelist query fail exactly
  as though WhatsApp were not installed, with no error saying so.
- **The add-pack intent must set a target package** (or use a chooser). An
  action-only implicit intent resolves to nothing, silently.
- **Kotlin block comments nest**, unlike Java's. A `/*` inside a doc comment —
  say, in a path example — swallows the rest of the file.
- **Animated WebP cannot be re-encoded on Android.** An oversized animated
  sticker can be archived but never repaired to fit WhatsApp's limit.

### Archive format

```
stickervault-YYYYMMDD-HHMM.zip
├── manifest.json
└── stickers/
    └── <sha256>.webp
```

Content-addressed, so duplicates collapse and integrity is self-verifying. The
format is deliberately dumb — plain files plus one JSON descriptor. Do not
replace it with a proprietary container; being readable without this app is the
entire point.

### Verifying the WebP parser

```bash
python tools/verify_webp_offsets.py
```

`WebpProbe.kt` reads dimensions and the animation flag from raw byte offsets. If
those are wrong it does not crash — it silently mislabels the whole library. This
generates real WebP files in every container form and checks the parse against
Pillow. Requires Pillow.
