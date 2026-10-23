# SMS Filter (Android)

Offline SMS spam filter and messenger for a phone that gets ~50 Persian advertising SMS per hour.
Ads are classified **on the device** and kept out of the inbox; bank, OTP and government messages are
protected by a hard guard that runs before any model.

Package: `ir.smsfilter` · Kotlin + Compose · minSdk 26 · targetSdk 35 · no network permission.

## Why it exists

Filtering by sender fails in Iran: the same shortcode sends both ads and real notices, and banks keep
changing numbers. So classification is **content-first**, with the sender only as a tiebreaker.

## Safety rules (encoded in tests and the eval gate)

1. Never hide anything that looks like money, a code or a government notice.
2. Filtered messages are **kept**, never destroyed — they live in the app's own `Ads` page and can be
   restored with one tap.
3. Every decision is explainable: each filtered message shows the score and the reasons.
4. A saved contact is never filtered by content, and never filed under Promotions.
5. Fully offline. No analytics, no network permission.

## How the filter decides

```
SMS arrives (default-SMS role) → persist → classify
  1. hard guard      OTP / transaction / government / delivery lexicon, allowlisted contacts
  2. sender rules    explicit blocklist, learned ad senders
  3. char n-gram     logistic regression on hashed 2–5 grams, int8 weights, bundled in assets
  4. learned words   phrases from messages the user marked as ads
```

Output is `IMPORTANT` / `SPAM` / `UNSURE` (unsure passes but is flagged). Ads get one throttled digest
notification instead of a banner each.

## Features

- Inbox with tabs: All / Transactions / Codes / Promotions (contacts stay in All), per-tab counts
- Search, pin, mute, archive (with undo), swipe actions, multi-select, block sender or keyword
- Contact picker for new messages, dual-SIM chooser, per-message actions, in-thread search, drafts
- Ads page: search, restore, block sender, delete; blocklist page for numbers and keywords
- MIUI-style metrics (92dp rows, 40dp avatars), true-black theme, Persian-safe bidi handling
- "This is advertising" on any conversation teaches the model what you consider spam

## Build and test

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./scripts/gate.sh          # unit rules -> filter-power eval -> assembleDebug
./scripts/gate.sh --install
```

`tools/gate_eval.py` fails the build when spam recall or important-kept drops below pinned thresholds,
so UI work cannot silently weaken the filter. `tools/mirror.py` runs the same rules and weights on the
host in Python for fast tuning; it must agree with the Kotlin model.

## Credits and inspiration

- **Original idea:** *FilterSMS* — the concept this project grew out of.
- **Launcher icon:** [Papirus icon theme](https://www.opendesktop.org/p/1662847/) (the artwork is used as supplied,
  transparent, and adapted to Android's icon layers).
- **UI inspiration:** Xiaomi's Messages app (`com.android.mms`). Its layout metrics (row height, type scale, badge
  shapes) and feature set were studied from the APK; no assets, strings or code were copied.
- **Built with:** [Hermes Agent](https://hermes-agent.nousresearch.com) by
  [Nous Research](https://nousresearch.com) — the agent wrote the classifier, the Kotlin/Compose messenger, the
  test gates and the tooling in this repository, pair-programmed with me.

## Notes and limits

- Personal project, Persian/Iranian SMS patterns. Not on Google Play (READ_SMS is restricted there).
- MMS bodies are not classified; RCS is visible only as a notification.
- On MIUI, disable battery restrictions for the app so the SMS role keeps working.
- No SMS data is stored in this repository; evaluation corpora are user-supplied and gitignored.
