# TEST-S2 — final pass (S2 + S2b + S2c + S2d) — verdict **PASS**

Repo: `~/coding/kotlin/smsfilter`
Run: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 gradle --no-daemon :app:testDebugUnitTest`
Fixture: `app/src/test/java/ir/smsfilter/addon/AddonRulesAdversarialTest.kt` (this file is mine)
Pass: independent final pass over DEV's four rounds — S2 (keyword list, ALL/ANY, regex), S2b (nested-quantifier reject, subject cap, newline split, mode trim), S2c (subject cap 200, quantified-group reject, max 2 quantifiers), S2d (`regexUsable` shared gate + editor validation).

## Verdict: PASS

**Suite: 131 tests, 0 failures, 0 errors.**

| class | tests | time |
|---|---|---|
| `AddonRulesAdversarialTest` (mine) | 61 | 12.1 s |
| `AddonRulesTest` | 38 | 13.0 s |
| `InboxPolicyTest` | 18 | 0.02 s |
| `GoldenTest` | 8 | 0.46 s |
| `ComposeIntentTest` | 6 | 0.05 s |

All four named ReDoS shapes are refused without running the engine, cost ≈ a literal keyword on the same body, and `regexUsable` (editor gate) agrees with `regexHit` (runtime gate) on all 13 sampled patterns. Residual risks are listed at the end and are cap-level, not unbounded.

## The two fixture edits (mine to make, nothing else in the file touched)

Both encoded pre-S2 semantics and were the only reds (baseline run: 126 tests, 2 failed, both `AddonRulesAdversarialTest`).

| line | test | was | now | why |
|---|---|---|---|---|
| ~424 | `s2RegexCatastrophicBacktrackingStaysUnderReceiveBudget` | `assertTrue((a+)+$ matches "a"×1000 / ×5000)` | `assertFalse`, message `"nested quantifier must be refused, not executed"` | S2b refuses `(a+)+$` outright, so a body of pure `a`s can no longer match. Cost assertions (fail bodies < 50 ms) kept unchanged. |
| ~512 | `s2UnknownModeFallsBackToAnd` | `assertFalse(" OR " → AND)` | `assertTrue(" OR " → OR)` | S2b trims `keywordMode` before comparing. Comment rewritten to say so; `XOR`/`""` → AND assertions kept. |

No other line of the file changed — the 5 new test methods below are appended, not modifications.

## 1. Cost of the four known RedOS shapes (measured through `AddonRules.matches`, 2000-char bodies)

| pattern | bodies | result | per call |
|---|---|---|---|
| `(a+)+$` | `a×2000`, `a×1999+"b"`, `a×1000+"!"+a×999` | **false** | 278–290 µs |
| `(a\|aa)+$` | same 3 | **false** | 276–279 µs |
| `a*a*a*b` | same 3 | **false** | 275–294 µs (one 1.9 ms first-call JIT outlier) |
| `a{0,200}a{0,200}a{0,200}b` | same 3 | **false** | 155–479 µs |

- 12 refused calls, **mean 457 µs each, ~5.5 ms total** (budget 50 ms) — test `s2cKnownRedosShapesAreRefusedCheaply`.
- Control on the same 2000-char body, plain literal keyword: **313 µs**. So the refusal gate itself costs ≈ nothing; the remainder is `Normalizer.normalize()` over the body, which every rule pays anyway.
- Sub-millisecond cost proves the pattern is never handed to `java.util.regex` (pre-S2 numbers for `(a+)+$` were 65 ms at 1001 chars and ~2 s at 5001).

## 2. `regexUsable` contract and refusal visibility

| pattern | `regexUsable` | `matches()` on a body it would hit | refused because |
|---|---|---|---|
| `\d{2}/\d{2}/\d{4}` | false | false | 3 `{` > cap 2 |
| `\d{4}-\d{2}-\d{2}` | false | false | 3 `{` |
| `(a\|b)+` | false | false | quantified group containing `\|` |
| `(` | false | false | `PatternSyntaxException` |
| `a`×121 | false | false | length > 120 |
| `""` | false | false | blank |
| `"   "` | false | false | blank |
| `^09\d{9}$` | true | **true** | — |
| `ab+` | true | **true** | — |
| `[0-9]{4}` | true | **true** | — |
| `(?:ab)+` | true | **true** | group prefix stripped before the shape check |
| `https?://\S+` | true | **true** | 2 quantifiers, no group |
| `\d{2}:\d{2}` | true | **true** | 2 quantifiers |

Test `s2dRegexUsableAndRegexHitAgreeOnEverySample`. Agreement is asserted the only honest way available: `regexHit` is private, so for each pattern I feed a body that a working regex would hit and require `matches() == regexUsable()`. Any drift between the editor gate and the runtime gate flips one of the 13 rows. Both call sites really are the same function — `grep regexUsable app/src/main` shows only `AddonRules.regexHit` and `MainActivity` line 1161.

Cap boundaries (`s2cQuantifierCapIsExactlyTwo`): 2 quantifiers pass (`\d{2}/\d{2}`, `a*b*c`, `\d{2}:\d{2}`), 3 are refused (`\d{2}/\d{2}/\d{4}`, `a*b*c*d`, `^\d{1}x{1}y{1}$`). Quantified-group rejection is about group content, not count: `(ab?)+` refused, `(ab)+` usable.

## 3. Editor claim — read from source, **UNVERIFIED-BY-TEST**

No Compose/Robolectric harness here, so this is a code read of `MainActivity.kt`, not a test result. Do not count it as UI coverage.

- **Refused regex does block the keyword dialog.** Line 1161 computes `refused` for every non-blank line when `keywordRegex` is on; line 1189 sets `TextButton(enabled = refused.isEmpty())`, so OK is disabled while any line is refused.
- **The message names the pattern.** Line 1183: `"too complex or invalid, will never match: ${refused.joinToString(" | ")}"` — each offending pattern is printed verbatim, in error colour. Refusal is no longer silent.
- Regex mode splits on newline only (line 1193), so `a{1,3}` survives the dialog — the old split-on-comma defect is fixed.
- **Gap found this pass (not a blocker):** the *outer* rule-dialog Save (line 1127) does not re-check `regexUsable`. A pattern can reach `keywords` without passing the keyword dialog by (a) typing keywords with the Regex chip off, then flipping the chip (line 1080) and pressing Save, or (b) importing JSON with an unusable pattern. Runtime still refuses it, so the outcome is "rule silently never matches", which is exactly the state S2d set out to make visible. One-line fix if wanted: gate the outer `confirmButton` on the same `keywords.filter { !regexUsable(it) }` check.

## 4. Regression

| property | test |
|---|---|
| plain literals `ab`, Persian `کد`, squash path `ت خ ف ی ف` → `تخفیف`, metachars literal, 200-char literal keyword | `s2dCapsNeverTouchTheLiteralPath` |
| legacy `keywordContains` still matches; list wins over legacy; AND vs OR intact | `s2dCapsNeverTouchTheLiteralPath`, `s2ListWinsOverLegacyField` |
| no input throws out of `matches()` — 11 hostile patterns × 7 bodies incl. 200 k chars, surrogates, NUL, ZWNJ | `s2dNoInputThrowsOutOfMatches` |
| S1 properties survived (`unusable sender/kw` fails the rule, digit folding, padded-ZWNJ inert) | unchanged S1/S2.3 tests, all green |
| load/save round trip, migration, blank-term unsatisfiability | unchanged S2.4 tests, all green |

## UNVERIFIED

- **Anything Compose/device**: dialog rendering, chip states, whether disabling OK is discoverable to a user, and whether the "too complex" text fits. Needs a device or Robolectric. Not claimed.
- **ART (device) timings.** All numbers are JVM 17, JIT warm. ART without JIT is typically slower, so the ~0.3 ms refusal and the ~27 ms allowed-worst-case both get worse on a cold debug build.
- **Android's bundled `org.json`** vs the `org.json:json` jar used by the prefs-backed tests — unchanged caveat from the previous pass.

## Residual risks (accepted)

1. **200-char subject cap.** Regexes never see text past 200 chars, so an end-anchored pattern (`abc$`) misses on long SMS. Short-code patterns (`^09\d{9}$`) are unaffected. Documented in code; upgrade path is RE2/J or a worker thread with a timeout.
2. **2-quantifier cap refuses useful date patterns**: `\d{2}/\d{2}/\d{4}` and `\d{4}-\d{2}-\d{2}` are unusable. This is a deliberate false refusal, now visible in the editor instead of silent, and the user can still match `\d{2}/\d{2}`. Cost: a phone-number/date rule with 3 groups cannot be written.
3. **Two-quantifier patterns still backtrack — the real remaining cost.** Measured: `a*a*b` **27.6 ms** on a 200-char body and **29.9 ms** on a 2000-char body; `.*.*b` **25.6 / 25.7 ms** (test `s2cKnownRedosShapesAreRefusedCheaply`). The 200-char cap binds, so cost no longer grows with SMS length, but 27–31 ms per rule is ~60 % of the 50 ms/SMS budget: two such rules on one SMS break it, and `.*.*b` is a plausible thing for a user to type. Acceptable for now because it is bounded and needs a deliberately overlapping pattern; upgrade to RE2/J or lower the subject cap to ~80 chars when addon rules see real traffic.
4. **Regex compiled per term per call**, no cache. Included in all numbers above; irrelevant at these sizes.
5. **Uppercase pattern never matches a lowercased body** (pre-existing, documented in `s2RegexIsCaseSensitiveOnLowercasedBody`); `(?i)` works.
6. Non-string entries in a `keywords` JSON array are silently dropped; an empty result widens a rule rather than narrowing it. Pre-existing.

## Files touched by this pass

- `app/src/test/java/ir/smsfilter/addon/AddonRulesAdversarialTest.kt` — the two assertions above, plus 5 appended tests (`s2cKnownRedosShapesAreRefusedCheaply`, `s2dRegexUsableAndRegexHitAgreeOnEverySample`, `s2cQuantifierCapIsExactlyTwo`, `s2dCapsNeverTouchTheLiteralPath`, `s2dNoInputThrowsOutOfMatches`).
- `TEST-S2.md` (this file).
- Main sources untouched by me.
