# BuzzKill module fix — plan (2 teams, gated)

Repo: `~/coding/kotlin/smsfilter`
Scope: `app/src/main/java/ir/smsfilter/addon/**` + the editor UI inside `MainActivity.kt`.
Hard rule: core filtering (`Classifier`, `Model`, `RuleSet`, `Learned`, `IncomingSms` decision logic) must not change behaviour.

Diagnosis (from `handoff.md` + code review):
1. Matching is one raw substring per rule -> no AND/OR, no regex, no whole-word, no multiple keywords.
2. No Persian normalization in the addon -> ZWNJ / ي→ی / ك→ک / ۱۲۳ variants silently fail.
3. `firstMatch` + `return` -> one rule, one action ever runs (BuzzKill runs all matching rules, in order).
4. Empty rule matches everything; "New rule" defaults to `copy_otp` with no condition -> swallows every SMS.
5. `AddonBus.dispatch` runs at `IncomingSms.kt:130`, notification posted at `:191` -> `dismiss`, `modify_text`, `alert` are no-ops or duplicated.
6. No cooldown, no trace -> cannot reproduce or debug why a rule did/didn't fire.
7. No notification-listener hook -> SMS only (accepted, out of scope for this pass).

Out of scope: screen/DND/BT/geofence/foreground-app conditions, batch/summarize/undo, non-SMS notifications, Room migration.

## Status

| step | state | evidence |
|---|---|---|
| S0 gap matrix | **PASS** | `BUZZKILL-GAP.md`, 36 rows, feasibility column filled in S6 |
| S1 normalize | **PASS**, committed `585ebf2` | 86 JVM tests, 0 failed; `TEST-S1.md`; defect (empty-normalized term matched every SMS) found by test team and fixed |
| S2 keyword list + regex | **PASS**, committed `c4daf44` | 131 JVM tests; `TEST-S2.md`; ReDoS (2 s/rule) found by test team, capped; residual: 2-quantifier O(n²) ~28 ms, 200-char subject cap |
| S3 all rules + cooldown | **PASS**, committed `6029d8f` | `TEST-S3.md` |
| S4 fire after notify | **PASS**, committed `9400dc7` | `TEST-S4.md` |
| S5 trace + dry run | **PASS**, committed `136d0f2`/`5f664c4` | `TEST-S5.md`; F1/F2/F3 findings fixed (`AddonTraceFixTest.kt`, 4 tests) |
| S6 feasibility | **PASS** | every row of `BUZZKILL-GAP.md` marked `done` (13) / `feasible now` (4) / `parked` (19); 4 promoted to S7+ |

## Teams

- DEV team: implements one step, writes JVM tests for it, keeps diff minimal, does not touch core filtering.
- TEST team: independent. Reviews the diff, runs the suite, writes adversarial tests in a separate file, reports PASS/FAIL with evidence. No code shipped to next step until PASS.
- SCOUT team (BuzzKill recon, continuous): runs REA on the BuzzKill APK and maintains `BUZZKILL-GAP.md` — a feature matrix of spec -> current addon -> verdict (have / weak / missing). Proposes features with the exact BuzzKill class or spec section as evidence. Writes no app code.
  - Source APK: `~/Downloads/Apps/Android/BuzzKill_v40.0.0_(364)_Mod.apk` (sha256 `384cab92ecf3...cba61`, v40.0.0 / 364, pairip-wrapped)
  - Existing spec: `~/Documents/BuzzKill_reverse_engineering_spec.md`
  - Tools: `rea` CLI at `~/.npm-global/bin/rea` (`rea --help`), plus MCP `mcp__rea__*` tools when available. JADX/apktool are NOT installed — no decompiling to disk.
  - Hard limit: static analysis only. No execution, no network.
- Collaboration rule: a scout proposal enters the plan only after DEV confirms feasibility (<= 1 file, <= ~80 LOC, no new dependency, no core-filtering change). Otherwise it goes to `Parked` with the reason. SCOUT may not mark its own item as planned.
- UX team (added Nov 5): drives the installed app on Waydroid and judges what JVM tests cannot — element placement, Persian labels and direction, tap flow, empty/error states, contrast and touch sizes. Reports with screenshots. Runs after each UI-touching step, on the build the test team just passed.
  - `waydroid app install` silently fails when the versionCode is unchanged: bump before install.
  - Capture with `QT_QPA_PLATFORM=wayland spectacle -b -n -f -o <png>`; Waydroid ADB is unauthorized and `waydroid shell` needs root, so there is no input automation — taps must be done by the user or via the desktop input tools.
  - Verdict goes to `TEST-UX.md` with a screenshot per finding.

Gate command (test team, every step):
```
cd ~/coding/kotlin/smsfilter
export ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
$HOME/tools/gradle/gradle-8.9/bin/gradle --no-daemon :app:testDebugUnitTest
```
`adb` device is currently NOT attached, so no on-device install in this pass. Final full gate: `bash scripts/gate.sh` (unit rules + filter power + release build).

## Steps

### S0 — Feature gap matrix (SCOUT, runs alongside S1..S5)
- Output file: `BUZZKILL-GAP.md` in the repo root.
- One row per BuzzKill feature area: keyword matching (tree, modes, locations), rule conditions, action configs, execution semantics, stateful plugins, storage/history/trace, integrations.
- Columns: feature | BuzzKill evidence (class or spec section) | current addon state (file:line) | verdict (have / weak / missing) | proposed smallest change | dev feasibility (filled by DEV).
- SCOUT does not stop the code steps; it feeds S6.

### S6 — Scout-driven plan update (SCOUT + DEV)
- DEV reviews every `missing` / `weak` row, marks `feasible now` or `parked: <reason>`.
- Only `feasible now` rows get promoted into S7+ with a test list. Everything else stays parked in the gap file.
- Membership in the plan requires: JVM-testable, no core-filtering change, no new dependency, <= ~80 LOC.

### S1 — Normalize before matching
- `AddonRules.matches`: compare `Normalizer.normalize(sender/body)` and the rule term, on both sides; fold Persian/Arabic-Indic digits.
- Keep `senderContains` / `keywordContains` fields as-is (no schema change yet).
- Dev tests: ZWNJ keyword matches plain; `ي` matches `ی`; `۱۲۳` digits match `123`; `ك` matches `ک`.
- Test team adversarial: mixed case + ZWNJ + digits in one message; empty term must not match everything; regex-free terms with `\` must not crash.

### S2 — Keyword list with AND/OR + regex
- Replace `keywordContains: String` with `keywords: List<String>` + `keywordMode: "AND"|"OR"` + `keywordRegex: Boolean`.
- Backward compat: old saved rules with `keywordContains` migrate to `keywords = [value]`, mode AND.
- Dev tests: OR matches one of two; AND requires both; regex `^\\+98` matches; invalid regex must not throw (treated as no match).
- Test team adversarial: empty keyword list; duplicate keywords; regex with catastrophic pattern bounds (guard length), Persian keyword inside a longer word.

### S3 — Execution semantics + cooldown + inert empty rules
- `AddonBus.dispatch`: run every matching rule in priority order (not just the first), each rule may carry an `actions: List<String>` (multi-action), `action` remains action[0] for compat.
- Add `cooldownSeconds: Int` per rule; skip rule while cooldown active (persist last-fired time in prefs).
- A rule with zero conditions does not match unless `matchAny: Boolean = true`.
- Dev tests: two rules both fire; empty rule inert; cooldown blocks second fire inside window and allows after.
- Test team adversarial: rule ordering, disabled rule in the middle, cooldown persistence across reload, self-test still green.

### S4 — Fire after the notification exists
- Move the `AddonBus.dispatch` call in `IncomingSms` to after `nm.notify(...)` (`:191`), so `dismiss` / `modify_text` / `alert` act on a real notification.
- Do not change filtering, insert, or digest logic.
- Dev tests: none possible in JVM for ordering -> add a unit test asserting actions receive the notify id, and document the manual check.
- Test team: build + diff review; verify no change to `isSpam` / insert / decision path.

### S5 — Trace: why did (not) fire
- Keep last 20 decisions in prefs: timestamp, sender, rule id/name, matched, failed condition.
- Features page: "Recent decisions" list, plus a per-rule test button (dry-run against a sample body).
- Dev tests: trace ring buffer caps at 20, newest first, no crash on empty.
- Test team: full `scripts/gate.sh` green, release build, no filter-power regression.

## S7+ promoted from the gap matrix

S6 pass over `BUZZKILL-GAP.md`: 13 rows `done`, 4 `feasible now`, 19 `parked`. Only the four below enter the plan, ordered by value per LOC. All four are additive fields on `AddonRule` (`AddonRules.kt`), JVM-testable, no new dependency, no core-filtering touch.

### S7 — `-term` negation in the keyword list (~12 LOC) ← do this one first
- **Gap row:** AND / OR / NOT (`KeywordMatching$Combination.not`).
- **Why it matters:** Iranian bank/OTP SMS end with an unsubscribe footer (`لغو11`, `لغو ۱۲`), and marketing SMS reuse the same words (`کد`, `بانک`, `تخفیف`). Today the only way to avoid the footer is to delete the rule part. With negation one rule reads *`کد` AND NOT `لغو`* — OTP copy stays on, marketing stays out.
- **Change:** a term prefixed `-` must be absent. In `conditions()`, split terms into positive/negative, ignore blank negatives, keep AND/OR semantics over the positives, all negatives must miss.
- **Tests:** `-لغو` blocks the footer SMS and leaves the clean OTP SMS matching; only-negative term list (`-لغو`) is not inert and matches any SMS without the word; `-` alone or `- ` (blank after strip) is ignored, not "match nothing"; OR mode + one negative.
- **LOC:** 12.

### S8 — whole-word keyword matching (~15 LOC)
- **Gap row:** whole-word (`RegexMatcher.d`, `(\W|^)…($|\W)`, IGNORE_CASE).
- **Why it matters:** `رمز` currently fires on `رمزنگاری` and `رمز عبور` marketing text fires OTP rules; `کد` fires on `کدپستی`. Word mode is what makes a short Persian keyword safe.
- **Change:** `keywordWholeWord: Boolean` on the rule, save/load; non-regex terms match with `Regex("(\\W|^)" + Pattern.quote(kw) + "(\\W|$)")` on the normalized body, subject still capped at 200 chars via `regexHit`.
- **Tests:** `رمز` whole-word does not match `رمزنگاری`; matches `رمز: 1234`; ZWNJ/spaced evasion (`ر م ز`) still matches through `squash`; flag off keeps today's substring behavior (regression).
- **LOC:** 15.

### S9 — `keywordScope`: body / sender / any (~15 LOC)
- **Gap row:** target field / scope (`KeywordScope` 4 ordinals).
- **Why it matters:** bank sender IDs (`BankMellat`, `+983000…`) carry the identity, the body carries the action. One rule today must pick a field: `senderContains` *and* body keywords only AND together, so "sender is a bank, body says برداشت" needs two rules and duplicate actions.
- **Change:** `keywordScope: String = "body"` on the rule (`body` | `sender` | `any` = sender + `\n` + body); save/load; `conditions()` picks the field before normalizing.
- **Tests:** `any` matches a keyword present only in the sender; `body` (default) does not; `sender` ignores body hits; old saved rule without the field behaves as `body` (migration).
- **LOC:** 15.

### S10 — per-rule snooze `disabledUntil` (~12 LOC)
- **Gap row:** enable/disable + temporary override (`RuleDbRepository.i(RuleId, boolean, Instant)`).
- **Why it matters:** a noisy rule (auto-reply, alert) is usually wrong for an hour, not forever; today the only options are delete or a full disable that the user forgets to undo. Snooze keeps the rule and lets the cooldown-free path resume by itself.
- **Change:** `disabledUntil: Long = 0` on the rule; `matching()` and `missReason()` treat `now < disabledUntil` as "disabled" (reason `snoozed`); no timer, no service.
- **Tests:** snoozed rule does not match before its stamp and matches after; `missReason` says `snoozed`; a snoozed rule does not arm its cooldown; old rule with no field is unaffected.
- **LOC:** 12.

Not promoted (see `BUZZKILL-GAP.md` for the reason on each): keyword tree, per-term `matchType`, multi-range schedules, `Room`/history, all device-state conditions, notification listener.

### Round 2 additions (REA on the real DEX, 2 Nov)
- **S11 emoji condition (~8 LOC).** BuzzKill `Extra.Emoji` is presence-only (code-point scan). Strongest cheap Persian ad signal; pairs with the existing link/promo logic.
- **S12 phone-number-in-body (~12 LOC).** BuzzKill `Extra.PhoneNumber` = regex + digit count + date rejection. Lets a rule exclude "body is just a number".
- Parked deliberately: schedule `invert` flag (no SMS use), every extra needing ML Kit or notification payload (Language, ImageLabel, Category, GroupConversation, Ongoing, Media, Chronometer, Profile, CustomLayout, Urgent). BuzzKill `RulePriority` is persisted and never read, so our `sortedBy{priority}` is our own semantics, not parity. BuzzKill cooldown is in-memory, keyed by notification comparison, MUTE or DISMISS only — ours is per-rule and persisted, keep.
- Correction: BuzzKill's own word mode uses ASCII `\W`, so `رمز` matches inside `رمزنگاری` there too. Our `wholeWordHit` is stricter than BuzzKill; do not "fix" it back.


## Target platform
PHONE ONLY (compact widths ~320–430dp). Waydroid exists only so the app can be seen and tapped during development; it is not a desktop or tablet target. No wide-window layout, no navigation rail, no tablet work, and no passes that measure pixel/font bounds.

## Definition of done
- `:app:testDebugUnitTest` green including new adversarial tests.
- `scripts/gate.sh` green (filter power unchanged: recall 0.88 / kept 0.983 gate).
- Old saved rules still load and behave (migration test).
- `handoff.md` updated with what changed and the manual on-device checks still pending.
