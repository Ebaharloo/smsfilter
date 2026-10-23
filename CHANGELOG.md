# Changelog

All notable changes to SMS Filter. Versions follow `versionName` in `app/build.gradle.kts`.

## 0.3 — 2026-10-23

Responsiveness pass after a full code review. No change to the filter policy or its numbers:
gate stays at Gheychi 0.862 / PSSC 0.879 / own inbox 0.964 with 0 important messages hidden.

### Fixed — scrolling

- **The installed APK is now the R8-optimised release build.** A debug APK runs Compose
  unoptimised; scrolling the inbox on this phone measured **129 ms median frame / 96 % janky
  frames** in debug and **20 ms / 19.6 %** in release on the first pass, **9 ms / 2.4 %** once
  warm. `scripts/gate.sh` builds and installs the release variant from now on.
- **SIM detection is one binder call per screen**, not one per row and one per bubble.
- **Bidi isolation for previews is computed once, off the UI thread** when the inbox snapshot is
  built (inbox and Ads page), instead of regex work inside each row as it scrolls into view.
- **Contact names are resolved once per snapshot**, so a row no longer runs two digit
  normalisations on every recomposition.
- **Contact photos are only looked up for senders that are actually in the address book**, and
  never for alphanumeric senders — that provider query per row was the first-scroll stutter.

### Fixed — things you could feel

- **A new SMS now appears while the inbox is open.** The list is driven by a single
  `ContentObserver` on `content://sms` (120 ms coalescing), so a message that arrives no longer
  waits for the page to be reopened. An open conversation refreshes on the same signal.
- **Faster inbox after any action.** Archive / pin / mute / mark-read / filter now run one
  address-book sweep instead of two, and the sweep is cached for 60 s.
- **Faster thread open.** A conversation is read newest-first and stops at the 800-message
  window instead of scanning the whole thread and slicing it — visible on bank threads that
  hold thousands of rows.
- **Faster sender → conversation lookup.** It asks the provider once for every spelling of the
  number (`+98…`, `0…`, `98…`, `0098…`) instead of walking the SMS table row by row. The
  row-by-row match survives only as the last resort for odd spellings.
- **Notification appears sooner.** Incoming messages are classified, stored and notified on a
  worker (`goAsync`) instead of the broadcast main thread.
- **Contact photos no longer cost a provider query for senders that have no number**
  (alphanumeric IDs such as `.IRANCELL.` or `**bank**`).

### Fixed — correctness and safety

- **Deleting asks first.** Multi-select *Delete* and the conversation menu's *Delete
  conversation* now show a confirmation. Archive has an UNDO; deleting from the provider cannot
  be undone.
- **Settings tells the truth.** Permission and SMS-role state is re-read on `ON_RESUME` instead
  of once inside `remember{}`, so the page stops claiming "missing" right after you grant it.
- **Restoring an ad moves the right conversation.** The thread is resolved from the row that was
  just written, not by guessing from the number — the same number can exist under two spellings
  in two threads.
- **A failed address-book sweep is never cached**, and neither is a sweep whose cursor never
  opened. Previously a transient provider hiccup could make saved contacts look unknown for a
  minute, and their messages would be filtered out of the inbox instead of passing the contact
  guard.
- **The Ads page reads 2 000 stored ads instead of 300** and says "showing newest N" when it is
  truncated, so the header count and the list can no longer disagree.
- Blocking a sender from an ads row now says the stored ad stays (blocking is forward-looking).
- Clearing the composer removes the draft instead of storing an empty value.

### Known, not fixed

- Notification ids are `NOTIF_ID + address.hashCode()`; a hash collision would make two senders
  share one notification (both messages are still stored).
- `Store.onUpgrade` recreates the table, so a future schema bump wipes the stored ads history.
- `FilterService` and `IncomingSms` can both attempt to store the same message; `UNIQUE(ts, text)`
  makes it idempotent but it is one wasted insert.

## 0.2 — 2026-10-22

Messenger release: conversation list derived from the `sms` table (MIUI's conversations union
rejects `snippet`/`unread_count`), thread view with bubbles and day chips, per-conversation
draft, dual-SIM picker, pin/archive/mute/block, swipe actions with UNDO, multi-select, in-thread
search, Ads page with restore/re-check/delete-all, retroactive inbox scan, blocklist page,
category tabs (All / Transactions / Codes / Promotions), address-book and `sms:` link entry
points (ComposeIntent), notification actions (inline reply, mark read, copy code), filtered-ads
digest, MIUI-style visuals, font-size and theme settings, adaptive icon.
