# LinkedIn post — drafts

Three versions at different lengths. Pick one, edit it into your own voice, and
delete the rest. Swap the link for whichever is public at the time.

---

## Version A — the story (recommended)

I've changed phones four times in four years. Every single time, I lost my
WhatsApp sticker collection and rebuilt it from scratch.

If you've ever done this, you know the specific annoyance. Not important data.
Just four years of in-jokes, reaction images and things friends made, gone
because the only copy lived inside an app that dies with the handset.

So I built StickerVault. It pulls the whole collection into a plain zip you can
put on Drive, and restores what you want onto the next phone.

A few things I learned building it that I didn't expect:

**The metadata was hiding in plain sight.** I assumed pack names were locked in
WhatsApp's database, which needs root. They're not — every sticker carries an
EXIF chunk naming the pack it came from. 94.9% of my library had it, which was
enough to rebuild 433 packs without touching anything privileged.

**My library was 11,203 stickers.** I had guessed a few hundred. Content-hash
deduplication found almost no duplicates, because WhatsApp already dedupes on
disk — so that number is real.

**The most useful architectural decision was the dullest one.** The archive is
just WebP files named by their own SHA-256, plus one JSON file. That makes
integrity self-verifying: if the bytes don't hash to the filename, the file is
corrupt, and there's no checksum file to keep in sync. It also means the archive
outlives my app entirely. If this project dies tomorrow, the stickers are still
just files.

**And the honest limitation, which I put on the landing page rather than
hiding:** Android WhatsApp serves third-party sticker packs live from the
providing app, so uninstalling removes them. That's true of every sticker app on
Android and has been an open request since 2018. The zip is the durable thing;
the app is a replaceable adapter.

2.2 MB, no network permission — the OS itself makes it impossible for the app to
send your collection anywhere.

Built with Claude Code. Kotlin, Jetpack Compose, and a lot of reading of
WhatsApp's sticker contract.

🔗 [LINK]

#Android #Kotlin #OpenSource #MobileDevelopment

---

## Version B — short

Changed phones four times in four years. Lost my WhatsApp stickers four times.

So I built StickerVault: it archives the whole collection into a plain zip, and
restores what you pick onto the next phone.

The part I enjoyed most was discovering that pack names aren't locked away in
WhatsApp's database after all — every sticker carries its origin in an embedded
EXIF chunk. 94.9% of my 11,203 stickers had it, enough to rebuild 433 packs
without root.

The archive is deliberately boring: WebP files named by their own SHA-256, plus
one JSON manifest. Integrity verifies itself, and the files outlive the app that
made them.

2.2 MB. No network permission. Open source.

🔗 [LINK]

#Android #Kotlin #OpenSource

---

## Version C — technical

Some notes from building a WhatsApp sticker backup tool, in case they save
someone else the afternoon.

**Android quietly closed the door on /Android/ via SAF.** Grants for anything
under it fail — and when they do, `OpenDocumentTree` returns null rather than an
error. A null result and a refused grant look identical, which means "nothing
happens when I tap allow" is a state you have to handle explicitly.

**Package visibility bites harder than expected.** Since Android 11, an
action-only implicit intent to another app resolves to nothing unless you declare
`<queries>` AND set the target package. It fails silently, exactly as though the
other app weren't installed.

**WhatsApp tells you why it rejected a sticker pack** — but only through a
`validation_error` extra on the activity result. Use `startActivity` instead of
expecting a result and you throw that away, turning a specific diagnosis into
silence.

**Sticker metadata lives in the files, not the database.** Every WhatsApp sticker
carries an EXIF chunk with its pack name, publisher and emoji tags. Pack grouping
survives export without root.

**Content-addressed storage is underrated.** Naming files by their own SHA-256
made deduplication free, imports idempotent, and integrity self-verifying — one
design decision, three properties.

**And a security note:** my first archive entry-name filter accepted a trailing
newline, because `$` matches before a final newline in most regex flavours.
Anchored isn't the same as exact. Worth a test rather than a glance.

🔗 [LINK]

#Android #Kotlin #MobileDevelopment #Security

---

## Notes on posting

- The strongest hook is the four-phones detail, because it's specific and
  a lot of people have quietly had the same experience.
- Including the limitation reads as confidence, not weakness. It is also the
  thing most likely to prompt replies from people who know the platform.
- If you post the GitHub link while the repository is private, nobody can open
  it. Either make it public first or link the landing page instead.
