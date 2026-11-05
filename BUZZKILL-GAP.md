# BuzzKill → smsfilter addon: feature gap matrix

Source APK: `~/Downloads/Apps/Android/BuzzKill_v40.0.0_(364)_Mod.apk` (sha256 `384cab92…cba61`, v40.0.0 / 364).
Current addon: `app/src/main/java/ir/smsfilter/addon/{AddonRules.kt,SmsAction.kt,FeatureSelfTest.kt}`.
Static analysis only. No app code changed by this task.

## 1. Method note

Tools: REA 6.7.0 (`rea inspect-android-package`, `rea search-android-classes`, `rea inspect-android-class`, `rea inspect-android-method`), backed by `jadx-headless-mcp 0.7.1` jar at `~/.local/share/rea/android/jadx-headless-mcp-0.7.1-all.jar`.

- REA refuses Android work until `REA_JADX_MCP_JAR` is exported (error `capability_unavailable`); `rea doctor` reports `jadx: missing`. Set the env var to run anything.
- Full class inventory retrieved: 6280 classes, 1410 resources (`search-android-classes` with empty query). Manifest/package facts via `inspect-android-package`.
- `inspect-android-class` / `inspect-android-method` work per class. Large methods fall back to smali (`representation`, `fell_back: true`) — readable, used for `Matcher.k`, `Matcher.b`, `RuleFinder.b`, `Matcher$findFailure$2.j`.
- Could NOT inspect: `background.utils.Matcher` and `background.utils.RuleFinder` by those names (index lists them, JADX says "Exact class not found" — the live DEX classes are `background.utils.b` and `background.utils.e`, whose inner lambdas are named `Matcher$…` / `RuleFinder$…`). Same for nested sealed classes `KeywordMatching$Text`, `KeywordMatching$Combination$KeywordScope` (only their `$$serializer` twins resolve). Their shape was recovered from smali field refs plus `$$serializer.<clinit>` descriptors (which carry the real serial names).
- pairip wrapper (`com.pairip.application.Application`) not analysed. No execution, no network, nothing decompiled to disk.

## 2. Gap matrix

| feature | BuzzKill evidence (class / method / spec section) | current addon state (file:line) | verdict | proposed smallest change | dev feasibility |
|---|---|---|---|---|---|
| keyword model: list vs tree | `data.model.KeywordMatching$Combination` serializer descriptor = elements `combination` → `operation`, `not`, `items`; `RegexMatcher.c(KeywordMatching)` recurses over `Combination.h` (items). So keywords are a recursive boolean tree, not a flat list. | `AddonRules.kt:25-26` one `senderContains`, one `keywordContains` string | missing | `keywords: List<String>` + `mode` first; nested groups only if a rule needs it (`ponytail:` flat AND/OR covers 95%, upgrade to tree when a user asks for NOT) | parked: nested tree needs a new rule schema + editor UI; flat AND/OR already ships |
| AND / OR / NOT | same `Combination` node: per-node `operation` + boolean `not`, so arbitrary nesting and negation | `AddonRules.kt:143-177` — all active conditions ANDed, single term | missing | add `keywordMode: "AND"\|"OR"` over `keywords`; NOT = prefix `-` on a term | feasible now |
| match modes 0/1/2 | `Matcher.n(String text, String kw, KeywordType)` (class `background.utils.b`): ordinal 0 → `RegexMatcher.a(text).equals(RegexMatcher.a(kw))` (normalized **equality**, not contains); ordinal 1 → `RegexMode.f` if `RegexMatcher.b(kw)` (CJK detected) else `RegexMode.g`; ordinal 2 → `RegexMode.e` | `AddonRules.kt:146-152` substring only (contains/squash) | weak (OR-of-contains, no modes) | `matchType: 0=exact,1=word,2=regex` on the keyword match; exact compares whole normalized field | parked: a per-term `matchType` enum needs a field + editor; `keywordRegex` already expresses exact (`^…$`) and word mode |
| case sensitivity | folded into modes: `RegexMode.e` = IGNORE_CASE + DOT_MATCHES_ALL, `f` = IGNORE_CASE + LITERAL, `g` = IGNORE_CASE + quoted. No per-keyword boolean exists on `KeywordMatching$Text` (fields: `text`, `scope`, `matchType`). | no case option; `Normalizer.normalize` folds case for Latin | have (implicitly, case-insensitive always) | none; keep case-insensitive, document it | done |
| whole-word | `RegexMatcher.d(kw, RegexMode.g)`: if not CJK → `Pattern.quote(kw)` wrapped `(\W\|^)…($\|\W)`, IGNORE_CASE; Latin/Cyrillic `\W` works, Arabic/Persian letters are `\w` in ICU so boundaries work too | absent — `body.contains(kw)` matches inside longer words | missing | one regex branch: `Regex("(\\W\|^)" + quote(kw) + "($\|\\W)")` for word mode (same file, ~4 lines) | feasible now |
| target field / scope | `KeywordMatching$Text.g: KeywordScope`, 4 ordinals in `Matcher.k`: 0→title arg, 1→text arg, 2→package arg, 3→list arg (iterates strings). No separate bigText/subText scope observed. | `AddonRules.kt:143-152`: sender and body only, hardcoded | weak | `scope: "body"\|"sender"\|"any"` on the term; `any` = sender+body concat | feasible now |
| text normalization | `RegexMatcher.a(String)`: strips U+200E/200F/202A-202E/200B/FEFF, maps ‘’‚‛‹› → `'`, “”„‟«» → `"`. **No** Persian letter or digit folding. | `AddonRules.kt:144,149-151` `Normalizer.normalize` + `Normalizer.squash` (Persian/Arabic variants, ZWNJ, digits) | have (ahead of BuzzKill) | none — do not regress; add digit folding test only | done |
| app / package selection | `Matcher.g(List<PackageName>, AppType, String pkg)`: empty list → true; `AppType.e` = only listed, `AppType.f` = all except listed (`data.model.a.c`/`.d`) | SMS-only: `senderContains` substring, no package concept; `AddonRule` has no app field | missing (N/A for SMS) | none for SMS; if notification listener lands, reuse AppType semantics | parked: no package concept in an SMS-only app |
| time schedule | `utils.TimeSchedule` = `Map<DayOfWeek, Set<TimeBlock>>` + `boolean` (`e`,`f`); `Matcher.m`: `if schedule.c() return true`, per-day set, inclusive `start <= t <= end` | `AddonRules.kt:155-168`: `daysOfWeek` CSV + single `startHour`/`endHour` | weak | replace 3 fields with `ranges: List<{days,startMin,endMin}>`; keep old fields as migration input | parked: multi-range-per-day needs a new schema + editor; one window covers quiet hours |
| overnight ranges | `Matcher.m` has **no wrap-around**: same-day `LocalTime` compare only. Overnight must be expressed as two blocks (e.g. 22:00-23:59 + 00:00-06:00) — spec §12.9 claim of overnight support is wrong | `AddonRules.kt:161-167` explicitly wraps (`h >= start \|\| h <= end`) | have (different semantics) | keep wrap (user-friendly), but note it diverges from BuzzKill; do not "fix" | done |
| location / geofence / wifi | `RuleLocation` sealed: `Anywhere`, `Wifi{List<String> ssids, boolean present}`, `Geofence{...}`; `Matcher$findFailure$2.j` reads `WifiManager.connectionInfo.SSID`, strips quotes, `present` inverts | absent | missing | none for SMS | parked: needs location permission + ConnectivityManager, not JVM-testable |
| bluetooth | `RuleBluetooth{List a (names), List b (addresses), boolean c (connected)}`; matched against `AndroidBluetoothManager.e` (`Leh.a` name / `.b` address) | absent | missing | none for SMS | parked: needs BLUETOOTH_CONNECT and a system service |
| screen state | `RuleScreenState.ScreenOn\|ScreenOff` vs `PowerManager.isInteractive()` | absent | missing | none for SMS | parked: PowerManager call, not JVM-testable, irrelevant to an SMS-only filter |
| call state | `RuleCallState.OnCall\|OffCall` vs `AudioManager.getMode() == 2` (MODE_IN_CALL) | absent | missing | none for SMS | parked: AudioManager call, not JVM-testable |
| DND | `RuleDndState.DndEnabled\|DndDisabled` vs `NotificationUtils.x()` | absent | missing | none for SMS | parked: needs notification-policy access grant |
| ringer | `RuleRingerState.e:int` vs `AudioManager.getRingerMode()` (exact int equality) | absent | missing | none for SMS | parked: AudioManager call, not JVM-testable |
| proximity | `RuleProximityState` via `Matcher.a(b, RuleProximityState, …)` (sensor listener; body not decompiled — unconfirmed which sensor API) | absent | missing | none for SMS | parked: sensor listener |
| table / face-down | `RuleTableState` via `Matcher.b(b, RuleTableState, …)` (unconfirmed internals) | absent | missing | none for SMS | parked: sensor listener |
| microphone | `RuleMicrophoneState.Recording\|NotRecording` via `AudioManager.getActiveRecordingConfigurations().isEmpty()` | absent | missing | none for SMS | parked: needs RECORD_AUDIO to read active recordings |
| orientation | `RuleOrientationState.Landscape\|Portrait` via `Resources.getSystem().configuration.orientation` | absent | missing | none for SMS | parked: Resources.getSystem() config, not JVM-testable, irrelevant to SMS |
| foreground apps | `RuleForegroundApps{List<String> e, boolean f}` via `UsageStatsManager.queryEvents` cache in `utils.b`; `f` = "open" mode; requires usage access | absent | missing | none for SMS | parked: needs usage-access grant |
| extras set | `Matcher.k` type-switches 16 `KeywordMatching$Extra` variants: `Category, Chronometer, CustomLayout, DetectedCategory, Emoji, FromContact, GroupConversation, Image, ImageLabel, Language, MediaNotification, OngoingNotification, PhoneNumber, Profile, TextLength, Urgent` | addon has `onlyFromContacts`, `onlySpam`, `onlyNotSpam`, `otpRequired` (`AddonRules.kt:30-35`) | weak | map `onlyFromContacts`→FromContact, `otpRequired`→regex `\d{4,8}`; skip Language/ImageLabel (needs ML Kit) | parked: remaining extras (Category, Language, Image, GroupConversation) need notification payload or ML Kit; FromContact/OTP/length already ship |
| text length | `KeywordMatching$Extra$TextLength{scope, comparison, length}`; `Matcher.l`: ordinal 0 → `len > length`, ordinal 1 → `len < length`. Single threshold, not a min/max range | `AddonRules.kt:153-154` min and max on body | have (superset) | none | done |
| action set present in DEX | `data.model.*Configuration` (37): `Alarm, AlwaysMute, BarsWallpaper, BatchAtTimes, BatchEvery, Cooldown, CopyVerification, CustomAlert, Dismiss, HeadscratchTask, KeepIf, KeepLatest, LiveUpdate, MacroDroid, MultiTool, PocketCheck, PressButton, RebootRestore, RejectCall, Reminder, RemoveHistory, Reply, Secret, ShareButton, SnoozeButton, Speak, Sticky, Summarize, Tap, Tasker, ToggleRule, Torch, Undo, Unsilence, UpdateRinger` + base `Configuration` (spec §6.2 omits `ShareButton`, `BarsWallpaper`) | 14 actions in `AddonRules.kt:190-273` (copy_otp, copy_body, autoreply, forward, mark_read, mute/unmute, pin/unpin, archive/unarchive, quarantine/restore, block_sender, dismiss, modify_text, alert, email) | have for SMS-relevant actions | none; keep | done |
| multiple actions per rule / MultiTool | `MultiToolConfiguration.e: List<Configuration>`; `RuleFinder.o` expands each child into a scored `hi1` entry (spec §12.11) | one `action: String` + `actionParam` (`AddonRules.kt:40-41`) | missing | `actions: List<String>` with `action` kept as `actions[0]` for compat | done |
| multiple matching rules | `RuleFinder.b` iterates **all** expanded configs, `findFailure` per rule, adds every match to a `LinkedHashSet` (dedupe by `RuleId`) and a result list — no early exit | `SmsAction.kt:47-53` (`firstMatch` → execute → `return`) and `AddonRules.kt:180-183` | missing | loop all matches, execute each, drop the `return` | done |
| rule priority / ordering | `data.model.RulePriority` enum with 3 constants; `RuleFinder.b` preserves expansion order, **no sort call found** in `b()`, and `a()` only filters. Priority semantics unconfirmed — `rea trace-android-references` on `RulePriority` would confirm | `AddonRules.kt:182` `sortedBy { priority }` | have (own ordering) | keep; treat priority as explicit user order | done |
| enable/disable + temporary override | `RuleDbRepository.i(RuleId, boolean, Instant)` and `j(RuleId, boolean)`; rule fields `j` (enabled), `k` (bool), `l` (Instant until), `m` (Boolean) — spec §6.1 | `AddonRule.enabled` only (`AddonRules.kt:23`) | weak | add `disabledUntil: Long`; `matches` returns false while `now < disabledUntil` | feasible now |
| cooldown (stateful) | `CooldownConfiguration{Duration, NotificationComparison, Behavior}`; `NotificationComparison` = 4 enum constants | absent | missing | `cooldownSeconds: Int` + last-fired map in prefs, skip while inside window | done |
| other stateful plugins | classes present: `plugins.keep.a` (KeepIf), `plugins.keep_latest.a`, `plugins.undo.a`, `plugins.sticky.a`, `plugins.summary.a`, `plugins.live_update.a`, `plugins.batch.BatchContentWatcher/BatchAtTimesPlugin`, `plugins.snooze_button.SnoozeButtonPlugin` (+`SnoozeOption.Ask\|Time`) | absent (one-shot actions only) | missing | out of scope for SMS v1 | parked: each is a background/stateful plugin over a history DB |
| persistence of rules | Room: `data.db.RuleDb`, `RuleDbRepository` (add/update/delete/duplicate/getAll, enable-disable), `AutoBackupManager` | `AddonRules.kt:45-115` JSON in `SharedPreferences` under `addon_rules_json` | have (simpler) | none | done |
| history + fire counts | `data.db.HistoryDb` (30+ queries incl. `observeRuleFireCountsSince`, time-range and package filters), `background.HistoryManager` | absent | missing | optional: reuse if a rules screen ever needs counts | parked: needs a new persistent store; no SMS decision uses counts |
| trace / why-didn't-it-fire | `Matcher.findFailure` returns one `TraceCode` per failing condition (codes seen `e..r`: package, time, location, bluetooth, screen, call, dnd, ringer, microphone, foreground, orientation, keyword, proximity, table); `RuleFinder$findMatchingRules` writes `TraceEntryType.e/f/g` entries with rule id + plugin id | absent | missing | keep last N decisions `{ts, sender, ruleId, failedCondition}` in prefs; reuse per-condition enum | done |
| triggers | `background.utils.NotificationTrigger` = `NEW_NOTIFICATION`, `OLD_NOTIFICATION`, `<obfuscated literal, ordinal 2>`, `CHRONOMETER`, `DISMISS_NOTIFICATION`; per-plugin support gated by `Plugin$Meta.g` | SMS receive only: `IncomingSms.kt:130` → `AddonBus.dispatch` | weak (1 trigger) | none for SMS; if listener lands, gate by trigger like BuzzKill | parked: needs a NotificationListenerService (out of scope this pass) |
| dismiss / removal handling | `NotificationService.onNotificationRemoved` → dismiss plugins; trigger `DISMISS_NOTIFICATION` | `dismiss` action fires at `IncomingSms.kt:130`, i.e. **before** `nm.notify` at `:191` | weak | move dispatch after `notify` (already planned S4) | done |
| self-test / dry run | none found in DEX (dev-only in addon) | `FeatureSelfTest.kt` assert-based checks; `AddonRules.execute(dryRun=true)` | have | none | done |

## 3. Top 5 gaps that matter for SMS-only use

1. **Keyword tree with AND/OR (and NOT)** — Persian spam/legit decisions are per-phrase: `بانک` AND (`برداشت` OR `واریز`). One substring cannot express that, and OR-ing two rules duplicates actions. Evidence: `KeywordMatching$Combination{operation, not, items}`.
2. **Match modes + whole-word** — bank/OTP messages need regex (`\d{6}`, `^\+98`, `کد تایید`) and word boundaries so `رمز` does not fire on `رمزنگاری`. Evidence: `Matcher.n` ordinals 0/1/2 and `RegexMatcher.d` mode `e/f/g`.
3. **Run every matching rule, not the first** — one SMS legitimately needs copy-OTP *and* mark-read *and* alert; `firstMatch` + `return` silently drops all but one. Evidence: `RuleFinder.b` collects all matches into a LinkedHashSet.
4. **Target scope per keyword (body vs sender) + single-threshold length** — `senderContains` exists but only as substring with the same matcher, so "sender starts with +98" or "body longer than 120 chars" cannot be written cleanly. Evidence: `KeywordScope` 4 values, `Matcher.l` GREATER/LESS.
5. **Cooldown / last-fired state** — without it, an OR-heavy rule set plus auto-reply at 2 a.m. can answer every retry SMS from a spammer; BuzzKill gates this per rule with `CooldownConfiguration{Duration, NotificationComparison, Behavior}`.

(Honourable mention, already planned S5: per-condition failure codes — BuzzKill's `TraceCode` shows this is the normal way to debug a rule.)

## 4. Corrections to `BuzzKill_reverse_engineering_spec.md`

1. §12.4 match order is wrong. Real order in `Matcher$findFailure$2.j`: package → time schedule → location → bluetooth → screen → call → DND → ringer → microphone → foreground apps → orientation → keyword → proximity → table. Spec puts keywords before extras and omits most device conditions.
2. §12.4/§12.8 `l(text,min,max)` range check does not exist. `Matcher.l` is `KeywordMatching$Extra$TextLength` with a `comparison` enum (GREATER → `len > n`, LESS → `len < n`) and a `scope`. Length is an extra, not a rule-level field.
3. §12.2 method table is mislabelled: `k` is "evaluate one `KeywordMatching` item (Text or Extra)", not "match a full Rule"; the whole-rule entry is `f(rule, notif)` (→ `findFailure == null`); `d` is keyword-tree matching with `(KeywordMatching, List, AppType, TimeSchedule, …)`.
4. §12.2 lists `f` as "whole-word boundary check" and `o` as "precompile regexes". In the live class `f` is full-rule match, `o` is `reloadRules(List<Rule>)`, and there is no standalone whole-word method — boundaries live inside `RegexMatcher.d` mode `g`.
5. §6.3/§12.5 describe keywords as a flat list with an AND/OR flag and per-keyword `caseSensitive`/`wholeWord`/`location` flags. Reality: `KeywordMatching` is a sealed tree — `Text{text, scope, matchType}` leaves inside `Combination{operation, not, items}` nodes. No per-keyword case flag; whole-word is derived from CJK detection, not a stored boolean.
6. Scopes are 4 (`title`, `text`, `package`, list-of-message-strings), not `title/text/bigText/subText/package`. Evidence: `Matcher.k` ordinals 0-3.
7. §12.9 "overnight ranges are allowed" is wrong: `Matcher.m` compares `LocalTime` inside one day's block set, no wrap. Multiple ranges **per day** are supported (`Map<DayOfWeek, Set<TimeBlock>>`).
8. §12.12 "for rule in matching in priority order" is unproven: `RuleFinder.b` appends matches in expansion order to a `LinkedHashSet`; no sort observed. `RulePriority` (3 constants) is stored but its consumer is unconfirmed.
9. §6.2 action list is incomplete: `ShareButtonConfiguration` and `BarsWallpaperConfiguration` exist as `Configuration` subclasses.
10. §7/§12.13 fixate on `POSTED`/`REMOVED`; there are 5 `NotificationTrigger` values incl. `CHRONOMETER`, `OLD_NOTIFICATION` and a third whose name literal is obfuscated (ordinal 2 — unconfirmed, likely "updated").
11. §12.3 says the profile carries "sender/contact resolution"; only `Extra$FromContact` (and `Extra$PhoneNumber`) consume contact/number data — the profile object built in `matchesKeyword$2` carries bitmap, groupConversation, customView, ongoing, media, category, profile (work/personal), chronometer, posted time. Exact contact-resolution call unconfirmed.
12. §0/§12.1 obfuscation note is half right: `background.utils.Matcher`/`RuleFinder` and `data.model.KeywordMatching$Text*` exist only as index rows / `$$serializer` classes; the inspectable implementations are `background.utils.b`, `background.utils.e`, and the serial-name descriptors. Anything read from the old spec's `Matcher`/`RuleFinder` letter tables should be re-checked against these names.
13. Normalization is narrower than the addon's: `RegexMatcher.a` only strips bidi controls and normalises curly quotes. It does **not** fold `ي→ی`, `ك→ک`, Arabic-Indic digits or ZWNJ. Do not attribute the addon's Persian normalization to BuzzKill in later docs; keep `Normalizer` as the project's own improvement.

## Round 2 findings

Same APK, sha256 `384cab92…cba61`, v40.0.0 (364). Static only (REA `inspect-android-method` / `inspect-android-class` / `trace-android-references`, JADX 0.7.1; smali fallback where flagged). All classes below resolve by their indexed names — `background.utils.b` = Matcher, `background.utils.e` = RuleFinder, `defpackage.rv` = cooldown plugin, `defpackage.sv` = its builder.

### R2.1 Match-mode semantics, verbatim (Q1, Q2)

`background.utils.b.n(String text, String kw, KeywordType)` — java representation, complete:

```java
int o = keywordType.ordinal();
if (o == 0) return RegexMatcher.a(text).equals(RegexMatcher.a(kw));          // mode 0
RegexMatcher m = this.b;                                                    // hudson: RegexMatcher field
if (o == 1) {                                                               // mode 1
    String t = RegexMatcher.a(text);
    return m.d(kw, m.b(t) ? RegexMode.f : RegexMode.g).e.matcher(t).find();
}
if (o != 2) { d.k(); return false; }
return m.d(kw, RegexMode.e).e.matcher(RegexMatcher.a(text)).find();          // mode 2
```

`background.utils.RegexMatcher.d(String kw, RegexMode)` — the Pattern factory, java representation, complete:

```java
String n = a(kw);                       // normalise
Regex cached = this.b.get(n + "-" + mode); if (cached != null) return cached;
if (mode == RegexMode.e) {              // REGEX
    /* trims leading and trailing '/' off n */
    regex = new Regex(n.subSequence(i, len + 1).toString(),
                      Set.of(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL));
} else if (mode == RegexMode.f) {       // LITERAL
    regex = new Regex(n, Set.of(RegexOption.IGNORE_CASE, RegexOption.LITERAL));
} else if (this.a.c(n)) {               // WORD, keyword contains CJK (dead branch — see below)
    regex = new Regex(Pattern.quote(n), Set.of(RegexOption.IGNORE_CASE));
} else {                                // WORD
    regex = new Regex("(\\W|^)" + Pattern.quote(n) + "($|\\W)", Set.of(RegexOption.IGNORE_CASE));
}
```

`RegexMatcher.a(String)` = strip U+200E/200F/202A-202E/200B/FEFF (9 chars), then six curly single quotes → `'` and six curly double quotes → `"`. Nothing else.

Answers:

1. **Mode 0** is equality of the **whole field** to the whole keyword, both sides passed through `RegexMatcher.a` first. No `trim`, no case fold, no digit/letter fold, no substring. A trailing space or `ي` vs `ی` breaks it. **Mode 1** on a single-word keyword is a bounded *search* (`find()`), not equality: it matches that word anywhere in the field. So they differ for every input except when the field is exactly that word. (Mode 0 also matches empty-vs-empty: `"" .equals("")` — a blank keyword matches a blank field.)
2. In `n` mode 1, the CJK test is run on the **normalised text**, not on the keyword: `m.b(t)`, where `b` = `containsCJK` sampling `str.charAt` at stride `len/5` (min 1) with a per-char `isCharCJK(C)`. So a **CJK message body** silently degrades word mode to `RegexMode.f` = `Pattern.quote(kw)` + `LITERAL|IGNORE_CASE` = plain substring. The `RegexMode.g` branch inside `d` (`this.a.c(n)`, CJK in the *keyword*) is therefore almost unreachable.
3. Word-mode boundary expression is exactly `(\W|^)…($|\W)` with `Pattern.quote`, flags **`IGNORE_CASE` only**. `DOT_MATCHES_ALL` is mode `e` only. There is **no `UNICODE_CASE` and no `UNICODE_CHARACTER_CLASS`**. In `java.util.regex` that makes `\w` ASCII-only (`[a-zA-Z_0-9]`), so **every Persian/Arabic letter counts as a boundary char**: `(\W|^)رمز($|\W)` matches inside `رمزنگاری`. BuzzKill's "whole word" is effectively a substring match for Persian SMS, and the same missing `UNICODE_CASE` means `IGNORE_CASE` only folds ASCII. Do not copy this expression — the addon's `wholeWordHit` (`AddonRules.kt:288-300`, `isLetterOrDigit`) is already the correct version.
4. Mode 2 trims one run of leading `/` and trailing `/` (a `while` over `charAt` from both ends) before compiling; a keyword like `/^\+98/` is accepted as regex sugar.

| feature | evidence | SMS-applicable verdict | smallest useful change | dev feasibility |
|---|---|---|---|---|
| mode 0 = whole-field equality | `background.utils.b.n` ord 0: `RegexMatcher.a(text).equals(RegexMatcher.a(kw))` — normalises both sides, then `String.equals`. Whole field vs whole keyword, no trim, no case/digit fold | expressible today only via `keywordRegex` `^\Q…\E$`, which bypasses `Normalizer` entirely (case + Persian folding lost) | third `keywordMode` value `EXACT` = `Normalizer.normalize(field) == Normalizer.normalize(kw)`, 2 lines in `conditions()` next to `wholeWordHit` |  |
| mode 1 = bounded search, CJK text degrades to substring | `b.n` ord 1: `d(kw, b(t) ? f : g).e.matcher(t).find()`; `b(t)` = `containsCJK(normalisedText)` | partial: addon word mode is stricter (Unicode-aware) and never degrades; that is a deliberate improvement, not a gap | none |  |
| word-mode regex has no Unicode flag | `RegexMatcher.d` else-branch: `new Regex("(\\W\|^)" + quote(kw) + "($\|\\W)", IGNORE_CASE)` | BuzzKill's word mode is broken for Persian; addon already correct | none — keep `wholeWordHit`; add one self-test asserting `رمز` does **not** hit `رمزنگاری` |  |
| mode 2 = raw regex, `/…/` tolerated | `RegexMatcher.d` mode `e`: trims `/` ends, `IGNORE_CASE + DOT_MATCHES_ALL`, used with `find()` | have (`keywordRegex` via `regexHit`, `find`-style) | accept one leading+trailing `/` before compiling (`trim('/')`) if `/…/` input shows up in imported rules |  |
| keyword scope (field selector) | `KeywordMatching$Text{f: String text, g: KeywordScope, h: KeywordType}`; `b.k` ord 0 → arg `p2` (title/sender), ord 1 → `p3` (text), ord 2 → `p4` (package, null ⇒ false), ord 3 → iterates `p5` (list of message strings, empty ⇒ false) | sender+body only; no package/list concept in SMS | `scope: "body"\|"sender"\|"any"` per term only if a rule needs it; sender/body split already exists as two hardcoded fields |  |
| empty `Combination` sentinel | `b.k` `Combination` branch: `if (item.equals(Combination.j)) return true` before reading `f` (Operation) and `h` (items) | n/a (addon `matchAny`) | none |  |

### R2.2 Every `Extra` subtype in the DEX (Q3)

`b.k` type-switches the `KeywordMatching$Extra` sealed type; `b$a` is the notification profile carrier passed in (`b$a` fields: `a` hasImage, `b` Bitmap, `c` groupConversation, `d` customLayout, `e` fromContact, `f` ongoing, `g` media, `h` category String, `i` NotificationProfile, `j`/`k` urgency model handle, `l` "no urgency model" flag, `m` chronometer lambda).

| Extra | payload | what `b.k` does | computable from an SMS (sender+body)? |
|---|---|---|---|
| `TextLength` | `{scope: KeywordScope, comparison: Comparison, length: Int}` (`g`,`h`,`i`) | scope 0 → `b.l(this, p2)` (title/sender), scope 1 → `b.l(this, p3)` (body); any other scope → `np.w("Unsupported scope for text length")` + null | **yes** — addon `minLength`/`maxLength` cover it |
| `PhoneNumber` | none (object) | iterates `p5` strings, `RegexMatcher.c` (a static phone regex) over each, trims match, `length > 6`, `Character.isDigit` count `> 6`, then rejects anything that parses as a date via `threeTen` `FormatStyle` x4 | **yes** — `otpRequired`/regex roughly covers; a stricter "contains a phone number" predicate is ~10 lines of the same regex on body |
| `Emoji` | none (object) | iterates `p5`, `RegexMatcher.a(str).codePoints()`, any code point where `RegexMatcher.e(int)` is true → hit | **yes** — presence only, **not a count** |
| `FromContact` | none (object) | `profile.e` | **yes** — `event.fromContact` (`onlyFromContacts`) |
| `Language` | `g: String` (language code) | `utils.f.a(text)` suspend = ML Kit language ID, then `equals(extra.g)` | **no** — needs ML Kit; a script heuristic (Arabic vs Latin) is the cheap stand-in |
| `ImageLabel` | `g: ImageCategory` | `Bitmap` from `profile.b`, `utils.e.c(bitmap)` = ML Kit labelling, `contains(extra.g)`; null bitmap ⇒ false | **no** |
| `Category` | `g: NotificationCategory` | compares `extra.g.e` (String) to `profile.h` (notification category string) | **no** — no SMS category; closest is a constant "message" |
| `Profile` | `g: NotificationProfile` | `extra.g == profile.i` (enum identity: work/personal) | **no** |
| `DetectedCategory` | `g: SmartCategory` | `utils.urgency.a.c(model, …)` returns `Set<SmartCategory>` from the bundled urgency model; `contains(extra.g)` | **no** — bundled ML model |
| `Urgent` | none (object) | if `profile.l` (no model) ⇒ false; else `utils.urgency.b.a(...)` + `utils.urgency.a.a(...)` on body text | **no** (needs the model); `onlySpam`/`otpRequired` are the SMS-side equivalents |
| `Chronometer` | none (object) | `profile.m.invoke(item)` — notification chronometer running | **no** |
| `GroupConversation` | none (object) | `profile.c` | **no** (MMS group thread at best) |
| `OngoingNotification` | none (object) | `profile.f` | **no** |
| `MediaNotification` | none (object) | `profile.g` | **no** |
| `Image` | none (object) | `profile.a` | **no** |
| `CustomLayout` | none (object) | `profile.d` | **no** |

Six carry payloads (`TextLength`, `Language`, `ImageLabel`, `Category`, `Profile`, `DetectedCategory`); the other ten are fieldless presence markers over notification state. Only `TextLength`, `PhoneNumber`, `Emoji`, `FromContact` are derivable from sender+body.

| feature | evidence | SMS-applicable verdict | smallest useful change | dev feasibility |
|---|---|---|---|---|
| extras: 16 variants, 10 fieldless | `b.k` instanceof chain over `KeywordMatching$Extra$*`; only six `iget-object` a payload field | addon has the 4 derivable ones (`onlyFromContacts`, `otpRequired`, min/max length) minus Emoji/PhoneNumber | add `emojiOnly`/`hasPhone` predicates reusing `RegexMatcher.e` semantics (code-point emoji test) if spam rules need them |  |
| TextLength is single-threshold + scope | `KeywordMatching.Extra.TextLength{g: KeywordScope, h: Comparison, i: Int}`; `b.k` scope 0/1 only, else NPE-ish log | have: min+max on body | none |  |
| `Emoji` = presence, not count | `b.k`: `codePoints()` any-match → boolean | n/a today | presence flag only if added; no count slider |  |
| `Language` / `ImageLabel` / `DetectedCategory` / `Urgent` = ML | `b.k`: `utils.f.a`, `utils.e.c(Bitmap)`, `utils.urgency.a.c(model,…)`, `utils.urgency.b.a(…)` | not SMS-computable | none for SMS v1 |  |

### R2.3 Cooldown (Q4)

`data.model.CooldownConfiguration{e: Duration, f: NotificationComparison, g: Behavior}` (serializer elements `duration`, `comparison`, `behavior`). Enums read from their `<clinit>`:

- `CooldownConfiguration.Behavior` = **`MUTE`** (0), **`DISMISS`** (1) — two constants, not three.
- `NotificationComparison` = **`SAME_APP`** (0), **`SAME_KEY`** (1), **`SAME_RULE`-equivalent** (2, name literal is an obfuscated string constant in `com.google.firebase.components.nx.PRSjuYChHLl`; the code compares `RuleId`), **`SAME_DESCRIPTION`** (3).
- `Settings.CooldownMode` = `ALWAYS`, `ONLY_IF_ACTIVE` (global setting that gates the whole feature to when BuzzKill is foreground).

Handler is `defpackage.rv` (`j()` returns `plugins.c<CooldownConfiguration>`; `sv` is the builder). `rv.b(ActionCoordinator, Configuration, Importance, notification, trigger, Set<String> liveKeys, RuleId, …)`:

- `Behavior.MUTE && !importance.e` ⇒ return empty action list and set `actionCoordinator.q.g = TraceCode.s`. `Importance` (`<clinit>`) = `IMPORTANT_AUDIBLE`(e=true), `IMPORTANT_BUT_MUTED`(e=true), `SUPPRESSED_BY_DND_OR_RINGER`(e=false), `NOT_IMPORTANT`(e=false) — so **MUTE does nothing for a notification that was never going to make a sound** (DND/ringer-suppressed or low importance); there is nothing to silence.
- `MUTE` path: scans its in-memory ring buffer `ij<a>` (`a{key, ruleId, package, Instant firedAt, notification ref, description, Behavior}`), compares each entry against the current notification per `NotificationComparison`, and if a record within `Duration.between(firedAt, now) < Duration` refers to a *different* notification ⇒ return `[TriggerAction.MUTE]`; else store a new record with `Behavior.MUTE` and return an empty list + `TraceCode.u`.
- `DISMISS` path: same scan but only entries whose stored `Behavior == DISMISS`; on a match returns `TriggerAction.MUTE` when the package is unchanged **and** the key is still in the live-notification `Set`, otherwise `TriggerAction.DISMISS_IMMEDIATELY`. No match ⇒ store the entry with `Behavior.DISMISS`, allow the rule normally.
- `rv.g(...)` is the "notification already posted" hook: `MUTE` → `ActionCoordinator.o(...,28)`; `DISMISS` → `NotificationHandler.w` check, then `actionCoordinator.n(notification)` (cancel) + `o(...,24)`, or just `o(...,28)` when the rule's own action already handles it.

So Behaviour = **silence the alert and keep the notification (MUTE)** vs **cancel the notification outright (DISMISS)**; nothing re-alerts. Keying is per *notification comparison*, not per rule: `SAME_APP` groups by package, `SAME_KEY` by notification key, ordinal 2 by `RuleId` (per rule), `SAME_DESCRIPTION` by trimmed description. State is an in-memory bounded ring buffer (`ij`), **not persisted** — a process restart clears all cooldowns.

| feature | evidence | SMS-applicable verdict | smallest useful change | dev feasibility |
|---|---|---|---|---|
| cooldown window | `CooldownConfiguration.e: Duration`; `rv.b`: `Duration.c(entry.d, now).compareTo(cfg.e) < 0` | addon has `cooldownSeconds` per rule, last-fire in prefs | none |  |
| cooldown key | `rv.b`: switch over `NotificationComparison` — `SAME_APP`→package, `SAME_KEY`→key string, ord 2→`RuleId`, `SAME_DESCRIPTION`→trimmed text | SMS side has only "per rule" today (`firedKey(rule)`) | optional `cooldownKey: "rule"\|"sender"\|"body"` = pick the persisted key (`firedKey` + sender hash) |  |
| cooldown behaviour MUTE vs DISMISS | `Behavior` = {MUTE, DISMISS}; `rv.b` returns `TriggerAction.MUTE` vs `DISMISS_IMMEDIATELY`; `rv.g` cancels via `NotificationHandler` | **no SMS analogue** — an SMS already arrived; "silence" would mean not notifying, which this app controls itself | map `DISMISS` → `dismiss` action, `MUTE` → skip `alert`; both already exist as actions |  |
| cooldown state lifetime | `rv.g` field `ij<a>` in-memory + `Set<String>` live keys; nothing in `RuleDb`/`HistoryDb` for cooldown | addon's prefs-based last-fire survives restart, BuzzKill's does not | keep prefs (better) |  |
| cooldown scope setting | `Settings.CooldownMode` = `ALWAYS`, `ONLY_IF_ACTIVE` | n/a | none |  |

### R2.4 Time schedule (Q5)

`utils.TimeSchedule{e: Map<DayOfWeek, Set<TimeBlock>>, f: boolean}` — serializer element names are literally **`days`** and **`invert`** (`TimeSchedule$$serializer.<clinit>`). `utils.TimeBlock{e: LocalTime, f: LocalTime}` = start/end.

- Per-day **set** of blocks ⇒ multiple windows per day, any subset of the 7 `java.time.DayOfWeek` values. No separate "enabled day list" — a day with an empty set is simply a non-matching day.
- `TimeSchedule.c()` returns **true when the map is empty or every day's set is empty** ⇒ "no time restriction". `Matcher.m` checks `c()` *before* anything else and returns true.
- `TimeSchedule.b()` returns `this` when `invert == false`; when `invert == true` it rewrites each day as the **complement** of the listed blocks inside the day bounds held in the static `LocalTime h`/`i`: empty day ⇒ one full-day block, else gaps before/between/after the blocks. So `invert` = "these are the excluded hours".
- `Matcher.m` then does `start <= t && t <= end` per block, same-day only ⇒ **no overnight wrap** (round-1 §12.9 correction stands).
- The boolean is **not** an enabled flag: `Rule.j` (enabled) and `Rule.k`/`l`/`m` (override) are separate rule fields.

| feature | evidence | SMS-applicable verdict | smallest useful change | dev feasibility |
|---|---|---|---|---|
| multiple windows per day, per-day sets | `TimeSchedule.days: Map<DayOfWeek, Set<TimeBlock>>`; `TimeBlock{start,end}`; `Matcher.m` iterates the day's set | addon has one `startHour`/`endHour` + `daysOfWeek` CSV | `windows: List<{days,startMin,endMin}>` when a user asks; one window covers quiet hours |  |
| `invert` flag | `TimeSchedule$$serializer` element `invert`; `TimeSchedule.b()` complements each day's blocks over the static day bounds | absent; addon's wrap-around (`h >= start \|\| h <= end`) is a different, friendlier semantic | `exclude: Boolean` on the window if a rule needs "never during these hours" |  |
| inclusive bounds, no wrap | `Matcher.m`: `localTime.compareTo(block.e) >= 0 && … <= block.f`, `LocalTime` only | addon wraps (documented divergence) | none — keep wrap |  |

### R2.5 Rule ordering / priority (Q6)

- The Room schema persists it: `CREATE TABLE IF NOT EXISTS rules (… priority TEXT NOT NULL …)` (also `folder TEXT`, no numeric order column).
- The only rules query in the DEX is `SELECT * FROM rules ORDER BY createdAt DESC` — **newest first, never by priority**.
- `background.utils.e.b(...)` (RuleFinder match loop) smali: zero occurrences of `sort`, `Comparator`, `RulePriority`, or any comparator call — matches are appended in input order to a `LinkedHashSet`.
- `data.db.RuleDbRepository` exposes add/update/delete/duplicate/getAll/observe/toggle/enable-override only; no ordering method. `RuleDbRepository.l(List)` maps entities; it does not sort.
- `RulePriority` = `HIGH`, `NORMAL`, `LOW` (`<clinit>`); `Rule.e` holds it. No runtime consumer found; treat it as stored/UI metadata.
- Separate mechanism that *is* used for ordering: `plugins.b` = `Comparator<Plugin>` over an `Integer[]` rank (`a`) + `Settings` (`b`) — it orders **plugins/actions within one rule**, not rules.

| feature | evidence | SMS-applicable verdict | smallest useful change | dev feasibility |
|---|---|---|---|---|
| priority column persisted | `rules.priority TEXT NOT NULL` in the generated schema; `Rule.e: RulePriority`; enum = HIGH/NORMAL/LOW | addon has `priority: Int` + `sortedBy` | none |  |
| priority has no matcher/DB consumer | `SELECT * FROM rules ORDER BY createdAt DESC`; `RuleFinder.b` smali has no sort/comparator; repo has no order method | addon's explicit `priority` sort is *more* than BuzzKill does — keep it, it is the documented, testable behaviour | none; add a comment that ordering is an addon choice, not parity |  |
| action order inside a rule | `plugins.b.compare(Plugin, Plugin)` over `Integer[]` rank + settings | addon already runs `actions` in list order | none |  |
| `folder` column exists | same `CREATE TABLE` statement | n/a | none |  |

### R2.6 Corrections to earlier rows

1. Round-1 row *whole-word* said "Arabic/Persian letters are `\w` in ICU so boundaries work". **Wrong.** The pattern is built with `IGNORE_CASE` only — no `UNICODE_CHARACTER_CLASS` / `UNICODE_CASE` — so `\w` is ASCII and Persian letters act as boundaries. BuzzKill's word mode is a substring match for Persian; `RegexMatcher.d` g-branch is the evidence.
2. Round-1 row *match modes* said mode 1 picks the CJK branch from the **keyword**. It tests the **normalised message text** (`m.b(t)`), so a CJK body degrades word mode to plain literal.
3. `Emoji` is **presence**, not a count, and carries no fields (round-1 "count vs presence" was unresolved).
4. `CooldownConfiguration.Behavior` has **two** constants (`MUTE`, `DISMISS`), not an unknown set; the enum lives only as a nested class, which is why `inspect-android-class` needs the dotted name `…CooldownConfiguration.Behavior`.
5. `NotificationComparison` ordinals are `SAME_APP`, `SAME_KEY`, <obfuscated literal, compares `RuleId`>, `SAME_DESCRIPTION` — the cooldown is keyed by notification comparison, **not** per rule as round-1 Top-5 #5 implied.
6. `TimeSchedule`'s boolean is the serialized `invert` field, not an "enabled" flag; `Matcher.m`'s early-out is `c()`, which means "map empty / all days empty".
7. `RulePriority` is persisted but has **no** matcher or query consumer; the only rules ordering in the app is `createdAt DESC`.
