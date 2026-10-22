# Farsi SMS Spam Filter (Android) — Features & Build Plan

## Problem statement (from user)
- ~50 SMS/hour, mostly Farsi promotional spam.
- Important messages (banks, OTP, government, deliveries) get lost in the flood.
- Filtering by sender number FAILS:
  - same number sends both spam and legit messages
  - legit senders vary / use different numbers
- Therefore: **classification must be content-first, sender is only one signal.**
- Need manual control: review what got filtered, override it, train the app.

## Non-negotiable safety rules
1. **Never auto-hide anything that looks like money/security/government.** Asymmetric cost: one lost bank/OTP message > 1000 spam let through.
2. Every filtered message is **kept** (quarantine log), never destroyed. Filtered ≠ deleted.
3. Every filtering decision is **reversible** and shows *why* it was filtered (matched rule / score).
4. Allowlisted senders can never be filtered by content, only by explicit user "block sender".
5. App runs fully offline. No cloud, no analytics, no network permission (except optional update check — off by default).

---

## FEATURE LIST

### A. Capture (input)
- A1. `NotificationListenerService` on messenger apps (Google Messages, Samsung Messages, Textra, whatever is installed) → reads SMS notification title (sender) + text.
- A2. Optional `READ_SMS` inbox scan (retroactive: classify last N messages, works on history).
- A3. Optional Phase-4 mode: become **default SMS app** → filter at DB level, archive spam into own folder, no notification ever fires.
- A4. Dual-SIM aware (subscription ID kept with each message).

### B. Normalization (must come before any matching)
- B1. Persian/Arabic character unify: `ي→ی`, `ك→ک`, `ۀ→ه`, `أإآ→ا`, `ؤ→و`.
- B2. Persian digits `۰-۹` → ASCII, Arabic-Indic `٠-٩` → ASCII.
- B3. Strip ZWNJ (`U+200C`), tatweel (`ـ`), diacritics (`U+064B–U+0652`), collapse whitespace.
- B4. Anti-evasion: join spaced-out letters (`ت خ ف ی ف` → `تخفیف`) for keyword check; normalize repeated chars (`!!!!`, `🔥🔥🔥`).
- B5. URL/shortlink extraction (`t.me`, `bit.ly`, `zil.ink`, `.ir`, `http(s)`, `www.`, bare `foo.com`).

### C. Classifier (three stages, in order)
- C1. **Hard guard — IMPORTANT (score = protected, skip all filtering):**
  - OTP/2FA lexicon: `رمز پویا`, `رمز یکبار مصرف`, `کد تایید`, `کد فعالسازی`, `کد ورود`, `CVV2`, `رمز دوم`, `کد امنیتی`, `otp`, `verification code`
  - Transaction lexicon: `واریز`, `برداشت`, `مانده`, `موجودی`, `انتقال`, `کارت به کارت`, `خرید`, `پرداخت`, `ریال`, `تومان` + amount pattern
  - Government/legal: `ابلاغ`, `ثنا`, `قوه قضاییه`, `سامانه ثنا`, `خدمات قضایی`, `مالیات`, `بیمه`, `تامین اجتماعی`, `پلیس`, `احضاریه`
  - Delivery/service: `مرسوله`, `پست`, `تحویل`, `نوبت`, `لغو نوبت`, `قرار ملاقات`, `پرواز`, `بلیط`
  - Any message < 25 chars from a non-promotional-looking sender → protected (too little evidence to judge)
  - Any message that matches allowlist sender → protected
- C2. **Sender rules:**
  - Allowlist (banks, telcos, gov, family/contacts) — seeded by app, editable. Supports exact number, shortcode, name-substring.
  - Contacts auto-allowlisted (if `READ_CONTACTS` granted).
  - Blocklist — user explicit, hard drop.
- C3. **Spam scoring (content):** weighted signals, sum ≥ threshold ⇒ spam
  - promotional lexicon (`تخفیف`, `حراج`, `فروش ویژه`, `تسهیلات`, `وام`, `بدون ضامن`, `شرط بندی`, `سرمایه`, `ارز دیجیتال`, `درآمد`, `کلیک کنید`, `ثبت نام`, `هدیه`, `برنده`, `قرعه کشی`, `کد تخفیف`, `ارسال رایگان`, `لاغری`, `مشاوره رایگان`, …)
  - opt-out boilerplate (`لغو11`, `لغو ۱۱`, `برای لغو`, `لغو اشتراک`) — very strong spam signal in Iran
  - URL present + promotional lexicon ⇒ strong
  - emoji density, ALL-CAPS-ish, repeated punctuation
  - message length & template repetition (same text to many numbers)
  - sender seen only with spam history → small positive weight (never enough alone)
- C4. **Learned model (Phase 3):** on-device char n-gram logistic regression / TFLite, trained only on *this user's* labels. Runs as tiebreaker for the "unsure" band. Retrains on-device (no server).
- C5. **Three-way output, never two:** `IMPORTANT` (pass), `SPAM` (quarantine), `UNSURE` (pass + subtle marker) — unsure messages are visible but flagged so the user can label them in one tap.

### D. Notification behavior
- D1. Spam → cancel the original notification, do NOT replace it with ours per message (no banner storm).
- D2. One **digest notification** per batch: "۳ پیام تبلیغاتی فیلتر شد — مشاهده" (tap → quarantine list). Throttled to e.g. 1 per 15 min.
- D3. Important/unsure → untouched (original notification, sound, badge preserved).
- D4. Quiet hours / per-sender mute, independent of classification.
- D5. Call/SMS-critical failsafe: if the app is killed or permission revoked → all messages pass through (fail-open, never fail-closed).

### E. Review UI (the part that makes it usable)
- E1. Tabs: **Inbox (kept)** / **Quarantined** / **Rules**.
- E2. Quarantine item shows: sender, time, text, `why` (matched rule names + score breakdown).
- E3. One-tap actions: `این مهم است` (release + learn), `تبلیغ است` (confirm + learn), `لغو فیلتر این فرستنده`, `بلاک فرستنده`, `افزودن کلمه به لیست`.
- E4. Batch actions: select-all spam, release-all.
- E5. Search + filter by sender, date, label.
- E6. Stats: filtered today/week, false-positive counter (messages user released) — the app's own quality metric.
- E7. Export/import: JSON/CSV of messages + labels (for debugging and for feeding the rule engine offline).
- E8. Undo snackbar on every destructive-ish action (rule changes, blocks).

### F. Learning loop
- F1. Every user action (release / confirm / block / keyword add) updates rules immediately.
- F2. Per-sender memory: sender's historical spam ratio, last N verdicts.
- F3. Auto-suggest: "sender X sent 14 ads in 3 days — block?" (never auto-block).
- F4. Model retrain trigger: ≥50 new labels, on-device, background, silent.

### G. Ops / correctness
- G1. Classification log (last 5000) stored locally in SQLite/Room.
- G2. Debug screen: paste a message → see normalized form, matched rules, score. Critical for tuning.
- G3. Replay mode: import a JSON of messages → run classifier → show confusion vs expected labels. Also mirrored in a Python script for fast iteration outside Android.
- G4. Battery: no polling; everything event-driven.
- G5. Backup/restore rules to a file (survives reinstall).

### H. Explicitly NOT in v1
- MMS media parsing, RCS, dual-app-messenger UI, cloud sync, translation, voice, Play Store release.

---

## ARCHITECTURE DECISION

**Do NOT fork AOSP Messaging.** Reasons:
- Full AOSP checkout + build ≈ 200 GB and hours per build; a fork means maintaining an entire messenger (MMS/RCS, attachments, threads) forever.
- Value is 100% in the classifier + review UI, ~0% in re-implementing a messenger.
- AOSP Messaging is not a drop-in library.

**Chosen: standalone Kotlin app, two layers, notification layer first.**
- Layer 1 (v1): `NotificationListenerService` → classify → cancel spam notification → own quarantine store + digest.
  - Pro: no default-app takeover, no MMS obligations, works alongside user's existing messenger, ~1 week of work.
  - Con: spam SMS still sits in the real inbox (hidden from notifications, not from the thread). Mitigated by quarantine copy + optional Phase 4.
- Layer 2 (optional, Phase 4): become default SMS app → true archive folder. Only if Layer 1 UX proves insufficient. This is a real decision point, not automatic.

**Android 13+ gotcha:** sideloaded APKs get *Restricted setting* on Notification access — must be allowed manually once (App info → ⋮ → "Allow restricted settings") before the listener can be enabled.

---

## BUILD PLAN

### Phase 0 — Toolchain (½ day)
- JDK 17, Android cmdline-tools, platform-tools, `platforms;android-35`, `build-tools;35.0.0`, `emulator`, `system-images;android-35;google_apis;x86_64` (has Messaging app + notifications).
- Install to `~/Android/Sdk` (no sudo: this session has no TTY for sudo auth; `~` has 52 GB free, SDK needs ~7 GB).
- AVD with KVM (present), 4 GB RAM, 8 cores.
- Gradle wrapper + Kotlin + Compose (Compose = less UI code than XML for this app).
- Deliverable: app builds, installs on emulator, shows empty screen.

### Phase 1 — Capture & data (1–2 days)
- NotificationListenerService, permission onboarding, DB schema (message, sender, score, verdict, reason, labels).
- **Dry run mode only** (nothing filtered) so real-world coverage can be measured.
- Export to JSON.
- Deliverable: app logs every SMS notification it sees, exports it.

### Phase 2 — Rules & safety (2–3 days)
- Normalizer + guard + sender rules + scorer, all driven by a **JSON rule spec** so the same spec runs in the Python mirror.
- Quarantine UI, release/confirm actions, debug/paste screen.
- Notification cancel + digest.
- Deliverable: spam gets hidden, banks/OTP never do, everything reversible.

### Phase 3 — Learning (2–3 days)
- Unsure band + labeling UI + per-sender memory + on-device model as tiebreaker.
- Metrics screen (FP counter).
- Deliverable: accuracy improves with use without any manual rule writing.

### Phase 4 — Decide (0–3 days, optional)
- Evaluate: is the real inbox still too noisy? If yes, default-SMS-app mode with archive folder. If no, stop here.

### Phase 5 — Harden & ship to the phone
- Fail-open checks, boot receiver re-arm, battery audit, signed APK, install on real device, backup of rules.

### Testing strategy (from day 1, not at the end)
- Unit tests on JVM for the normalizer/guard/scorer (fast, no emulator).
- `adb emu sms send <num> <text>` to inject SMS into emulator; real Farsi samples replayed via the in-app debug paste screen and the JSON replay mode.
- A Python mirror of the rule engine so the rule spec can be tuned against an exported CSV in <1 s per iteration instead of a rebuild.
- Golden-set file: labelled real messages (spam + bank + OTP + gov), run on every rule change. **Blocked until the user supplies ~50–100 real samples.**

## Data needed from the user (blocking for Phase 2 quality)
1. 30–50 real spam SMS (raw text + sender).
2. 10–20 real bank transaction SMS.
3. 10–20 OTP/2FA SMS.
4. 10–20 government/service SMS (ثنا، بیمه، پست، نوبت).
Redacted is fine (replace names/amounts), keep structure and wording.
