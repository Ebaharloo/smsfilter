# TEST-S7 — gate: `-term` negation

**Verdict: PASS** — 9 of 9 gates. S7-D1 (an *unusable negative* widening a rule to match everything) is fixed in `AddonRules.kt` and the defect pin is flipped to the fixed semantics. D2 (bare `-` dropped beside a usable sibling) accepted as implemented. G4 (squash false positive on a negated term) accepted, no guard. G1 remains a documented gap (no syntax to negate a term starting with `-`), plus the usual unverified UI/device surface.

- Repo `~/coding/kotlin/smsfilter`, tree at `92c64d9` + DEV's uncommitted S7 work (`AddonRules.kt`, `MainActivity.kt`, `AddonRulesTest.kt`). I edited `AddonRulesAdversarialTest.kt` (the flip) and this file; in DEV code only one comment in `conditions()` was corrected to match D2, no behaviour change.
- Command: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk $HOME/tools/gradle/gradle-8.9/bin/gradle --no-daemon :app:testDebugUnitTest`
- Suite: **226 tests, 0 failures, 0 errors, 0 skipped**. `AddonRulesAdversarialTest` 119 `@Test` (no test added in the flip: `s7DefectUnusableNegativeRegexWidensToEverything` is renamed to `s7UnusableNegativeFailsTheKeywordConditionInsteadOfWidening` with more assertions). Whole task 2 m 51 s.
- Every assertion is green, i.e. the tests pin *actual* behaviour. No test asserts the old widening bug any more; the renamed test asserts the fix (and `matching()` returning nothing), so a regression to the old code turns it red.

## Case table

| # | gate | result | evidence (test) |
|---|---|---|---|
| 1 | OTP survives, ad with `لغو` excluded, variant-proof | **PASS** | `s7NegativeTermSeparatesOtpFromAdInEveryVariant` — OTP plain / Arabic kaf+yeh / tatweel body match; ad plain, spaced, ZWNJ, letter-spaced (`ل غ و`), tatweel, Persian digits all fail. Negative also folded when typed as `-تاييد` or `-لغـو`. |
| 2 | AND, 2 negatives | **PASS** | `s7AndModeTwoNegativesFailOnEitherAndPassOnNeither` — either negative kills, both absent passes, missing positive still fails. |
| 3 | OR + negatives; only-negative list | **PASS** | `s7OrModeNegativesNeverJoinTheOr` — positives OR'ed, each negative still vetoes (`رمز، لغو 11` fails), negative is never OR'ed in as a positive; `-لغو` alone matches a clean body, fails on a hit. |
| 4 | `\-abc` literal; `-\abc` behaviour | **PASS (G1)** | `s7HyphenEscapeAndBackslashNegation` — `\-abc` = positive literal `-abc`, `keywordTerm(...).first == false`. `-\abc` = **negation of the literal `\abc`**; `-\-abc` = negation of the literal `\-abc`, so there is **no syntax for "must not contain `-abc`"** (G1). Sane but incomplete; see residual risks. |
| 5 | unusable terms: `''`, `'   '`, `-`, `--`, ZWNJ, tatweel, diacritics, ZWNJ+tatweel | **PASS with D2 note** | `s7UnusableTermsStayUnsatisfiable` — 8 terms × AND/OR × literal/regex = 32 combos, all false; alone never matches. In OR they do not widen. In AND `''`, `'   '`, ZWNJ, tatweel still fail the rule. **`-` and `--` are dropped, not fatal, when a usable sibling exists** (`کد AND -` == `کد`) — this contradicts the "unsatisfiable positive" wording; see D2. S1/S2 blank invariants re-run: legacy `keywordContains = "   "` is still "no condition" (true), a list term `"   "` is still unsatisfiable (false). |
| 6 | regex negatives; unusable after strip must not widen | **PASS** | `s7RegexNegativesGateTheSameStringAsTheEditor`: `-^09\d{9}$` excludes `09123456789` and `۰۹۱۲۳۴۵۶۷۸۹`, passes `hello`; editor and runtime gate the same stripped string (`keywordTerm().second` → `regexUsable`). `s7UnusableNegativeFailsTheKeywordConditionInsteadOfWidening`: `-(` alone, `-(` beside a hit positive, bare `-` + `-(` with a usable positive, in **AND and OR**, and `-<ZWNJ>` — all false, `matching()` empty, `missReason()` = `keyword`. |
| 7 | cooldown trace, dry-run `missReason`, zero-condition inertness | **PASS** | `s7TraceCooldownAndDryRunSeeNegationAsTheKeywordCondition`. Miss line: `15:09 · 123 · کد تخفیف، لغو 11 → no rule (keyword)`; no cooldown armed. Fire line: `15:09 · 123 · کد تایید شما: 12345 → OtpOnly: copy_body`; cooldown armed and blocks the next SMS. `missReason` = `keyword` on a negative hit, `null` on a clean body; `executeAll(dryRun = true)` = 1 and arms nothing. `AddonRule()` still matches `matches()`, still excluded by `matching()`, `missReason` = `no conditions`; `matchAny` still restores it. |
| 8 | cost, 20 rules × 2 negatives, 200-char body | **PASS** | `s7TwentyRulesWithTwoNegativesStayUnder100ms` — **5.08 ms** mean over 200 `matching()` calls (budget 100 ms), 20/20 rules match a clean body, 0/20 on a body carrying all 40 negative tokens. |
| 9 | full suite green | **PASS** | 226 tests, 0 failures, 0 errors, 0 skipped. |

## S7-D1 (fixed): unusable negative fails the keyword condition

A negative term that can never hit made the negation vacuously true, which — alone — was an always-satisfied keyword condition, so `matches()` said yes to every body and `matching()` ran the rule. Fixed in `AddonRules.kt` `conditions()`:

```kotlin
val negUsable = negTerms.all { t -> if (rule.keywordRegex) regexUsable(t) else Normalizer.normalize(t).isNotEmpty() }
val usable = parsed.any { it.second.isNotBlank() && !(it.first && it.second == "-") }
if (parsed.isNotEmpty() && (!usable || !negUsable)) active += "keyword" to false
```

i.e. a negative that cannot be evaluated makes the rule unsatisfiable, the same answer an unusable positive gets. The pin is flipped: `s7DefectUnusableNegativeRegexWidensToEverything` → `s7UnusableNegativeFailsTheKeywordConditionInsteadOfWidening`, now asserting `false` for:

| rule | old | now |
|---|---|---|
| `keywords = ["-("]`, regex | matches every body, `matching()` returns it | false, `matching()` empty, `missReason` = `keyword` |
| `["کد", "-("]`, regex, AND **and** OR | positive hit rescued the refused negative | false |
| `["کد", "-", "-("]` and `["-(", "-", "کد"]`, regex, AND **and** OR | bare `-` + refused pattern widened | false |
| `["-\u200C"]` (ZWNJ normalizes to `""`), non-regex | matches every body, fires | false, `matching()` empty |
| `["کد", "-\u200C"]`, non-regex, AND **and** OR | positive hit rescued it | false |
| `["کد", "-"]`, AND and OR | — | same answer as `["کد"]` alone (bare `-` dropped, D2) |

**No remaining widening path** in the keyword branch, by case (all covered above or by `s7UnusableTermsStayUnsatisfiable`):

- only negatives, all "usable" (`-abc`): `posOk` is `true` because `positives.isEmpty()`, and `negTerms.none(hit)` still gates on a real hit — a clean body passes, a hit fails (test 3).
- only negatives, any unusable: `negUsable` = false → rule false (was the bug).
- only junk (`-`, `--`, blank, ZWNJ): `usable` = false *and* `negUsable` = false → false. There is no shape where `positives` and `negTerms` are both empty yet `usable` is true (a term counted usable is either a positive or lands in `negTerms`), so `true && true` cannot be reached vacuously.
- positives present: AND needs every positive to hit, OR needs one; a refused/blank positive hits `false`, and blanks cannot accidentally match because `hit()` rejects an empty normalized kw.
- regex mode: `hit()` gates on `regexUsable`, and `negUsable` uses the same predicate, so "runtime can hit it" and "we consider it usable" cannot diverge.

## Bare-hyphen decision — I agree with the assertion, not with the model

`bareHyphenIsNoTermAndDoesNotWiden` (`AddonRulesTest.kt:353`) asserting `false` is right: a keyword list that is only junk must never become "match every SMS", and letting `-` mean "no condition" would make a one-character typo a global rule. Nothing in the app's UI makes that recoverable at a glance.

The implementation is **not** an "unsatisfiable positive": `keywordTerm("-")` is `(true, "-")`, which is dropped from both `positives` and `negatives`. Consequence (D2, **accepted**): `["کد", "-"]` behaves exactly like `["کد"]` — it does **not** fail the AND, which is what "unsatisfiable positive" would literally demand. A stray `-` in an otherwise working rule should not silently kill it, and junk with nothing else usable still makes the rule never match, so the code stays as is. The comment in `conditions()` (and `s7UnusableNegativeFailsTheKeywordConditionInsteadOfWidening`) now says exactly that: *ignored beside a usable sibling, never matches when alone*.

## G4 decision: squash false positive on a negated term is accepted

Re-checked, still reproduces and the fixture pins it: `["کد", "-12 34"]` does **not** match body `کد 1234`, and `["کد", "-1234"]` does not match `کد 12 34` (squash removes spaces before both the term and the body).

**Accepted, no guard.** A negative means "this term, as the engine matches it, must be absent", and the engine's predicate is the same `hit()` the positives use — `contains(normalized)` *or* `contains(squashed)`. Over-exclusion drops the rule's action (no copy, no reply, no block) rather than firing it wrongly, so it is fail-safe in the direction that matters. A guard (literal-only match for negatives, or requiring the term's own spacing) would make `-لغو 11` miss the ZWNJ/letter-spaced evasions that gate item 1 exists for, and would be more code than the feature. The opposite risk is smaller and visible: an OTP rule with a multi-word negative can silently miss — recorded in the risk table, not guarded.

## Half-done audit of the interrupted DEV work

Read the whole uncommitted diff, not just the last hunk. It is complete and consistent; nothing is stubbed, no `TODO`/`FIXME`, compiles clean:

- `AddonRules.kt`: `keywordTerm` + the reworked keyword block; comment on line 442 ("leftovers are dropped by the caller") matches the code.
- `MainActivity.kt`: 4 hunks, all needed and mutually consistent — `badRegex` and the dialog's `refused` both strip before `regexUsable`; `conditionSummary` splits positives/negatives; chip label prefixes `not `; hint text mentions the leading `-`.
- `AddonRulesTest.kt`: 8 new tests; the one flipped assertion is the bare-hyphen one discussed above.
- Cosmetic leftovers in the UI (DEV file, non-blocking): for a lone `-` the chip renders `not ` with an empty term, and `conditionSummary` renders `message does not contain ['']`. Imported `- ` / `--`-style rules can show the same. No functional impact.

## UNVERIFIED

- Compose editor behaviour (chip label, dialog hint, Save refusal for `-(`, `conditionSummary` text): code-read only; no Compose test harness in this repo and I did not launch a device.
- Real `SharedPreferences` JSON round trip for a negated rule: exercised through the `FakePrefs` proxy only. `save()` stores raw terms, so `-` prefixes survive verbatim — but that is proxy-level evidence.
- Anything requiring a real device: notification/channel side effects, clipboard, `SEND_SMS`.
- Performance numbers are JVM/JIT on this machine, not ART.

## Residual risks

| id | risk | notes |
|---|---|---|
| S7-D1 | unusable negative = match everything | **fixed and pinned**: unusable negative makes the rule false (alone, beside a usable positive, AND/OR, literal and regex). Reopen only if `hit()`/`negUsable` stop sharing a predicate. |
| S7-G1 | no syntax to negate a term that starts with `-` | `keywordTerm` only unwraps `\-` at index 0; `-\-abc` negates the literal `\-abc`. One-line fix if it matters: add `raw.startsWith("-\\-") -> true to raw.substring(2)` before the plain `-` branch. |
| S7-G2 | upgrade meaning-flip for stored keywords starting with `-` | literal → negation, no migration. A pre-S7 rule with keyword `-abc` (meaning "contains `-abc`") now means "does not contain `abc`"; with `-<unusable regex>` it is now inert instead of global (D1 fix), so the failure is a silent miss, not an over-fire. Still worth a one-time `load()` warning. |
| S7-G3 | terms are not trimmed at runtime | `keywordTerm` is a pure prefix parse, so ` -لغو` (leading space, JSON import only — the UI trims) is a *positive* for the text `-لغو` and silently never matches. |
| S7-G4 | negatives inherit the squash false positives | **accepted, no guard** (decision above). Measured: negative `12 34` also excludes body `1234`, and negative `1234` excludes `12 34`. Over-exclusion drops the action, never misfires; a multi-word negative can silently kill an OTP rule. |
| S7-G5 | negative regexes see only the first 200 chars | a phone-shaped tail after 200 chars is not seen, so the exclusion misses; inherited `MAX_REGEX_SUBJECT` shortcut. |
| S7-G6 | `-`/`--` dropped beside a usable sibling (D2) | **accepted**: junk is ignored when a usable sibling exists, and alone it makes the rule never match. Comment and test now say this; the earlier "unsatisfiable positive" wording is retired. |
| S7-G7 | cost is fine at 20×2 negatives (5.1 ms) but not measured with 40-regex-negative rule sets | each negative is one `normalize` + one `contains`/`matches`; regex negatives add compiled-pattern work per call. |
