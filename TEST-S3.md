# TEST-S3 — independent verification of S3 (all matching rules, multi-action, cooldown, inert empty rules, editor validation)

**Verdict: PASS** (independent test pass, no DEV code edited)

- Suite: `:app:testDebugUnitTest` → **162 tests, 0 failures** (fresh compile+run, 1m09s; adversarial class 85 tests, 0 failures).
- S3 coverage: 31 tests (24 in `AddonRulesAdversarialTest`, 7 in `AddonRulesTest`).
- All checks go through the public API (`AddonRules.matching/matches/allowedNow/markFired/executeAll/save/load`, `AddonBus.dispatch`), explicit `nowMillis` where the signature takes one (`dispatch` always uses the wall clock, so its cooldown path is driven by pre-marking the pref), no sleeps, no threads.
- Build: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk $HOME/tools/gradle/gradle-8.9/bin/gradle --no-daemon :app:testDebugUnitTest`

## Case table

| # | Attack | Result | Evidence (test) |
|---|--------|--------|-----------------|
| 1a | 3 rules match → every one runs, priority order | PASS | `s3DispatchExecutesEveryMatchingRuleExactlyOnceInPriorityOrder` — `markFired` writes `addon_fired_A, _B, _C` in that order (one write per executed rule, same loop body) |
| 1b | exactly once each | PASS | same test: one `addon_fired_*` write per rule; `s3FiringRuleAlsoSkipsLegacyPath`, `s3LegacySingleActionRuleStillFiresOnce` |
| 1c | disabled rule in the middle skipped, later rules still run | PASS | `s3DisabledMiddleRuleIsSkippedAndLaterRuleStillFires`, `s3NonMatchingAndDisabledRulesNeverAppear`, `s3Dispatch…` (priority 5 disabled between priority 1 and 20) |
| 1d | equal priority keeps saved order (stable sort) | PASS | `s3EqualPriorityKeepsSavedOrder` |
| 1e | matched-but-cooling rule does NOT fall through to legacy `SmsAction` list | PASS | `s3CoolingRuleDoesNotFallThroughToLegacyActions` (sentinel action, 0 calls), `s3CoolingRuleIsSkippedButLaterRulesStillRun` |
| 1f | legacy list runs exactly once when nothing matched | PASS | `s3NoRuleMatchedRunsLegacyPathExactlyOnce` |
| 2a | boundary: 59 999 ms blocked, 60 000 ms allowed | PASS | `s3CooldownBoundaryIsExact` |
| 2b | clock backwards keeps it blocked | PASS | `s3WallClockGoingBackwardsKeepsTheRuleBlocked` (−1 s, −60 s, −1 year) — documented fail-closed behaviour, not a bug |
| 2c | `cooldownSeconds = 0` never writes prefs | PASS | `s3CooldownZeroWritesNoPrefAndRulesUseSeparateKeys` |
| 2d | two rules do not share a cooldown key | PASS | same test: `addon_fired_a` ≠ `addon_fired_b` |
| 2e | deleting a rule leaves a stale `addon_fired_<id>` | CONFIRMED, non-blocking | `s3DeletedRuleLeavesAStaleCooldownPref` (key survives `save(emptyList())`; same id re-created starts cooling) |
| 3a | `matches()` true for zero conditions | PASS | `s3ZeroConditionRuleMatchesButNeverFires`, plus pre-existing `secondPassNoConditionRuleStillMatchesEverything` (kept, assertion untouched) |
| 3b | `matching()` false for zero conditions unless `matchAny` | PASS | `s3ZeroConditionRuleMatchesButNeverFires`, `s3MatchAnyRestoresAZeroConditionRule`, `s3SpacesOnlyConditionsCountAsNoCondition` |
| 3c | old install's condition-less rule (action `copy_otp`) fires nothing | PASS (behaviour confirmed) | `s3ExistingConditionlessRuleFromAnOldInstallIsSilentlyDead` |
| 3d | inert rule does not hijack the legacy list | PASS | `s3InertConditionlessRuleDoesNotClaimTheSmsSoLegacyStillRuns` |
| 3e | every `ensureDefaults()` rule has a condition | PASS | `s3DefaultsCreatedFromLegacyTogglesAreNotConditionless` |
| 4a | actions order preserved on execute path | PASS (structural + round-trip) | `s3ActionsCooldownAndMatchAnySurviveSaveLoad`; execute path is `for (a in rule.actions.ifEmpty { listOf(rule.action) })` with `rule.copy(action = a)` — order is the list order by construction |
| 4b | unknown action name does not stop the rest | PASS (no-throw) | `s3UnknownActionDoesNotStopTheRestOfTheRule` (dryRun, 3 actions incl. `no_such_action`), per-action try/catch in `executeAll` |
| 4c | legacy single `action` rule still fires once | PASS | `s3LegacySingleActionRuleStillFiresOnce`, `s3EmptyActionsListFallsBackToPrimaryAction` |
| 4d | save/load keeps actions + cooldown + matchAny | PASS | `s3ActionsCooldownAndMatchAnySurviveSaveLoad` (full data-class equality) |
| 5 | 20 regex rules × 200-char body cost | PASS — **3.4–5.1 ms** per `matching()` call across 3 runs (budget 100 ms, ~20× margin) | `s3TwentyRegexRulesOnA200CharBodyStayUnder100ms` (50 warmup + 100 measured calls, includes the JSON `load()` that dispatch pays per SMS) |
| 6a | outer Save cannot persist an unusable regex | CODE-READ PASS / UNVERIFIED-BY-TEST | `MainActivity.kt:1068` `badRegex`, `:1152` `TextButton(enabled = badRegex.isEmpty())` |
| 6b | chips cannot reach an empty action list | CODE-READ PASS / UNVERIFIED-BY-TEST | `:1119` `if (actions.size > 1) actions - a else actions`; init `rule.actions.ifEmpty { listOf(rule.action) }`; Save uses `actions.first()` |
| 6c | cooldown parses safely on empty/invalid input | CODE-READ PASS / UNVERIFIED-BY-TEST | `:1140` digits-only filter, `:1172` `toIntOrNull()?.coerceAtLeast(0) ?: 0` |
| 7 | full suite green, S1/S2 assertions intact | PASS | `git diff --numstat`: adversarial fixture **389 added, 1 deleted** — the single deletion is a stale comment line (`// Plan S3 makes zero-condition rules inert; until then this stays true`); all 61 pre-S3 cases keep their assertions |

## Deliberate regression statement: condition-less rules from an old install

Before S3, `AddonBus.dispatch` used `firstMatch()` → `matches()`, and `matches()` returns true for zero conditions. A rule with no conditions therefore claimed **every SMS** and, because it won the dispatch, also suppressed the legacy per-toggle actions (`addon_otp_autocopy` etc.).

After S3 the same rule is inert: `matching()` skips it, so it fires nothing and does not claim the SMS (the legacy list then runs instead). This is a **real user-visible behaviour change for that one rule shape**, and it is silent — nothing tells the user on upgrade. Mitigations, verified:

- `ensureDefaults()` never produces such a rule; all three migrated toggles carry a condition (`otpRequired`, `onlyNotSpam`, `onlyFromContacts+onlyNotSpam`) — `s3DefaultsCreatedFromLegacyTogglesAreNotConditionless`.
- The editor shows `no conditions — never fires` in the rule summary (`MainActivity.kt:1015`) and offers the explicit `Any SMS` chip → `matchAny`.
- Only reachable shape: a rule saved by hand-editing "New rule" (name + action, no condition) or imported JSON.

**Judgement: acceptable, not a defect.** The old behaviour (one empty rule consuming all SMS traffic and disabling the toggles) is worse than the new fail-closed one. Recording it here as a known, intentional regression for that shape; no migration is offered.

## Timing

Measured on the JVM test runner (`System.nanoTime`, 50 warmup calls then 100 measured, includes the `load()` JSON parse that `dispatch` pays on every SMS):

- 20 regex rules × 200-char body (realistic patterns: `code\d{4}`, `\d{2}:\d{2}`, `^09\d{9}$`, Persian `کد\w{2}`…): **3.415 / 4.871 / 5.143 ms** across three runs → PASS, budget 100 ms.
- Adversarial probe (not a pass gate, printed by the same test): 20 rules whose pattern is `a*a*b` against a 200-char body of `"a"` → **818 ms**. This is the real ceiling of the current cap: `regexUsable` allows exactly two quantifiers, and a 2-quantifier pattern costs **39–58 ms per rule** on a homogeneous 200-char body (S2c log). The 100 ms budget therefore holds for ordinary SMS text, not for that shape — see residual risk 7.
- Whole adversarial class: ~14 s wall (dominated by the 200 000-char bodies and the S2 ReDoS probes).

## Changes made to the fixture (test-only)

`app/src/test/java/ir/smsfilter/addon/AddonRulesAdversarialTest.kt`:
- `FakePrefs` gained `val writes` and records every editor `put*` key — the only execution observable available on the JVM.
- Added 2 S3 dispatch-evidence tests + `evT()` helper (execution order / exactly-once via `addon_fired_*` write order).
- Fixed one false-alarm assertion in `s3ActionsCooldownAndMatchAnySurviveSaveLoad`: the rule was built with `keywords = []`, but save/load migrates `keywordContains` into `keywords` (documented in S2), so full data-class equality could never hold. Added `keywords = listOf("کد")` to the fixture rule; no production behaviour involved.
- Updated one stale comment in `secondPassNoConditionRuleStillMatchesEverything` (assertions untouched).
- Sibling-agent S3 tests in the same file were reviewed and kept; all assertions are additions only (0 deletions vs `c4daf44`'s fixture, apart from one stale comment line).
- Second independent pass re-ran the whole suite after the last edit: **162 tests, 0 failures**, and added the `a*a*b` × 20 worst-case probe quoted under Timing (prints only, no pass gate).

## UNVERIFIED (no device / no emulator, no Robolectric)

- **All editor UI behaviour** (`AddonRuleEditor`): "Save disabled on bad regex", chip min-1-action, cooldown field, `Any SMS` chip. Verified by reading `MainActivity.kt` only; Compose is not exercised by any test.
- **Real action side effects**: clipboard write, toast, SMS send/forward, notification dismiss/alert, email chooser/app intent, `ThreadFlags` mute/pin/archive. These cannot run on the JVM — touching `ThreadFlags` raises `ExceptionInInitializerError` (its `<clinit>` parses a `content://` `Uri`, and framework statics in the unit-test android.jar return null through Kotlin's non-null check), and every `getSystemService` lookup returns null. Attempted and abandoned; not faked into success. The "one event per rule / priority order" evidence is therefore the `addon_fired_*` write order, not two real side effects.
- **Per-action execution order on a real run**: `dryRun` returns before `execute()`, so the order is proven structurally (loop + `rule.copy(action = a)`) and by list round-trip, not by observing two side effects in sequence.
- **Cooldown across a process restart** — asserted only at the prefs level (value read from `SharedPreferences`, not memory), no device restart.

## Residual risks

1. `markFired` writes even when the action threw (dispatch calls it unconditionally after `executeAll`): a rule whose only action fails still consumes its cooldown window. Not tested against a real failing action (see UNVERIFIED); by code-read this is deliberate ("one failing rule must not stop the rules after it") but it can silently swallow repeats for up to `cooldownSeconds`.
2. Cooldown is `System.currentTimeMillis()` — a clock jump forward re-arms a window early, a jump backward blocks longer. Fail-closed, documented, not adjustable.
3. Stale `addon_fired_<id>` keys are never pruned (one `long` per deleted rule). Harmless, unbounded in principle.
4. Editor cooldown field has no upper bound; an 11-digit entry overflows `toIntOrNull()` → `0` → cooldown silently becomes "every time". UI-only, unverified, low impact.
5. Multi-action `dryRun` and the `email` branch share `actionParam`/`emailTo`: a rule with `["forward","email"]` uses the same `actionParam` as the forward number *and* the email fallback recipient. Not part of the S3 attack list; flagged for review.
6. Regex cost measured on a desktop JVM; an ART device can be 2–5× slower. Realistic 20-rule set ≈ 5 ms × 5 ≈ 25 ms, still inside the 100 ms budget, but the headroom is smaller on low-end hardware.
7. The 100 ms budget is **not** shape-proof: `regexUsable` admits two unbounded quantifiers, and 20 × `a*a*b` on a 200-char homogeneous body measured **818 ms** (`s3TwentyRegexRulesOnA200CharBodyStayUnder100ms` probe). Real SMS text keeps the real path at ~5 ms, so this needs 20 hostile patterns to hurt, but the per-SMS budget is only enforced by pattern shape, not by a total-time cap. Fix direction if it matters: run `matching()` off the receive thread, or lower `MAX_QUANTIFIERS` to 1 / measure per-pattern width.
8. A rule with zero conditions saved by an old install is silent: no migration, no toast. The list row does say `no conditions — never fires` and offers `Any SMS`, so the warning exists but only if the user opens Features.
