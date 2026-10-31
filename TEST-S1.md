# TEST-S1 — second-pass independent verification (buzzkill normalization)

**Verdict: PASS.**

Pass 1 finding, one line: a rule term that normalizes to `""` (ZWNJ / diacritics / tatweel / bidi marks,
also space-padded) matched **every** SMS — 5 failing assertions, FAIL — and DEV's fix closes it.

Verified independently against the code, not the dev report. Suite: **86 tests, 0 failures,
BUILD SUCCESSFUL.**

The two known false positives (space-squash, spaces-only term) are asserted and reported as current
behaviour; they are not counted as passes for the defect.

## Fixture integrity (checked first)

- `app/src/test/java/ir/smsfilter/addon/AddonRulesAdversarialTest.kt` was **not** touched by DEV: still the
  same 27 test methods, and the five assertions that failed in pass 1 are still at the same lines (40, 48,
  54, 60, 66). mtime 21:17:43 — before DEV's `AddonRules.kt` fix at 21:21:25.
- `AddonRulesTest.kt` diff removes only the two `matchAll = false` tests (`anyModeMatchesOne`,
  `matchAnyAcceptsOneActiveCondition`). That removal is forced by dropping the `matchAll` field from
  `AddonRule`/JSON, and pass 1 already recorded it as pre-existing ("consistent with the pre-existing AND
  change"). mtime 21:09:44 predates the adversarial file. The file gained normalization coverage instead
  of losing assertions; its 20 remaining tests are intact. **Not weakened this pass.**
- No main source modified by TEST. `grep -rn "matchAll\|matchAny" app/src` → only a method name.

## What changed since pass 1

`AddonRules.matches()` (`app/src/main/java/ir/smsfilter/addon/AddonRules.kt:143-155`):

```kotlin
if (rule.senderContains.isNotBlank()) {
    val sender = Normalizer.normalize(rule.senderContains)
    active += sender.isNotEmpty() && Normalizer.normalize(event.sender).contains(sender)
}
if (rule.keywordContains.isNotBlank()) {
    val body = Normalizer.normalize(event.body)
    val kw = Normalizer.normalize(rule.keywordContains)
    active += kw.isNotEmpty() &&
        (body.contains(kw) || Normalizer.squash(body).contains(Normalizer.squash(kw)))
}
```

Correct: an empty-after-normalize term yields a **false** condition (not a skipped one), so a rule whose
only condition is such a term no longer returns `true` through `active.isEmpty()`. `kw.isNotEmpty()`
implies `squash(kw).isNotEmpty()` — `normalize()` trims, so a non-empty result always keeps a non-space
char, and `squash` only deletes `' '`. No `contains("")` path remains in either branch. Raw `isNotBlank()`
gates kept as claimed.

## New cases (appended to the adversarial file; 27 → 34 tests)

| # | Case | Expected | Actual | Test |
|---|---|---|---|---|
| 1 | keyword `"  \u200C  "` (spaces + ZWNJ) vs `"سلام"` and `""` | no match | no match | `secondPassPaddedZwnjKeywordAndSenderAreInert` |
| 2 | sender `"  \u200C  "` vs `"999"` | no match | no match | `secondPassPaddedZwnjKeywordAndSenderAreInert` |
| 3 | padded-ZWNJ keyword + `minLength = 1` on same rule (AND) | no match | no match | `secondPassPaddedZwnjKeywordAndSenderAreInert` |
| 4 | keyword `"\u0640\u064B\u0640\u0652\u0670\u0640"` (tatweel+diacritics only) | no match | no match | `secondPassTatweelDiacriticsMixIsInert` |
| 5 | same mix as `senderContains` vs `"123"` | no match | no match | `secondPassTatweelDiacriticsMixIsInert` |
| 6 | `"ک\u0640\u064B"` (mix, one letter survives) vs `"کد 42"` | match | match | `secondPassTatweelDiacriticsMixIsInert` |
| 7 | `"\u200C\u0640"` (ZWNJ+tatweel) vs any body | no match (normalizes to `""`) | no match | `secondPassOneCharNormalizedKeyword` |
| 8 | `"\u200C\u0640ک\u200C\u0640"` → `"ک"` vs `"کد 42"` / `"abc"` | match / no match | match / no match | `secondPassOneCharNormalizedKeyword` |
| 9 | `٠١٢` (Arabic-Indic) vs body `۰۱۲`; `۰۱۲` vs `012`; `012` vs `٠١٢`; sender `٠١٢` vs `012` | match | match | `secondPassDigitScriptsCollapseToAscii` |
| 10 | `٠١٢` vs body `۰۱۳` | no match | no match | `secondPassDigitScriptsCollapseToAscii` |
| 11 | valid keyword `کد` + sender only-ZWNJ on the SAME rule | no match (AND: unusable condition fails) | no match | `secondPassUnusableSenderFailsWholeRuleEvenWithValidKeyword` |
| 12 | control: sender `123` + keyword `کد` | match | match | `secondPassUnusableSenderFailsWholeRuleEvenWithValidKeyword` |
| 13 | mirror: valid sender `123` + keyword only-tatweel | no match | no match | `secondPassUnusableSenderFailsWholeRuleEvenWithValidKeyword` |
| 14 | `AddonRule()` with NO conditions vs empty sender/body | match everything (documented; plan S3 makes it inert) | match | `secondPassNoConditionRuleStillMatchesEverything` |
| 15 | keyword `"   "` / sender `"   "` (spaces only) | matches everything (raw `isNotBlank()` gate → zero conditions, documented) | match | `secondPassNoConditionRuleStillMatchesEverything` |
| 16 | squash path: keyword `123` vs body `1 2 3` | match (intended evasion bridge) | match | `secondPassSquashFalsePositives` |
| 17 | squash path: keyword `123` vs body `12 34` | **false positive** — matches | match (documented, real) | `secondPassSquashFalsePositives` |
| 18 | squash path: keyword `12 34` vs body `1234` | **false positive** — matches | match (documented, real) | `secondPassSquashFalsePositives` |
| 19 | sanity: keyword `1234` vs body `12 3` | no match | no match | `secondPassSquashFalsePositives` |

Cases 14–18 assert *current, documented* behaviour, not the S1 defect. Cases 17/18 are the honest
false-positive surface of `squash()`: it deletes spaces before comparing, so digits that were never one
number get joined (`"12 34"` → `"1234"`).

## Command and exact result

```
cd ~/coding/kotlin/smsfilter
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk \
$HOME/tools/gradle/gradle-8.9/bin/gradle --no-daemon :app:testDebugUnitTest
```

```
> Task :app:compileDebugUnitTestKotlin
> Task :app:compileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 1m 26s
24 actionable tasks: 2 executed, 22 up-to-date
```

`AddonRules.class` mtime 21:23:01 is newer than `AddonRules.kt` 21:21:25, so the tested bytecode contains
the fix even though `compileDebugKotlin` reported UP-TO-DATE.

Per-class XML (`app/build/test-results/testDebugUnitTest/*.xml`):

| Class | tests | failures | errors | skipped | time |
|---|---|---|---|---|---|
| ir.smsfilter.addon.AddonRulesAdversarialTest | 34 | 0 | 0 | 0 | 0.325s |
| ir.smsfilter.addon.AddonRulesTest (DEV) | 20 | 0 | 0 | 0 | 0.023s |
| ir.smsfilter.ComposeIntentTest | 6 | 0 | 0 | 0 | 0.117s |
| ir.smsfilter.GoldenTest | 8 | 0 | 0 | 0 | 0.409s |
| ir.smsfilter.InboxPolicyTest | 18 | 0 | 0 | 0 | 0.026s |
| **total** | **86** | **0** | **0** | **0** | |

All five pass-1 failures (`zwnjOnly…`, `paddedZwnj…`, `diacriticsOnly…`, `tatweelOnly…`,
`bidiMarkOnlySender…`) are now green with their original assertions untouched.

## Remaining risk (none blocking this step)

1. **Spaces-only term is still a match-everything rule.** `keywordContains = "   "` fails the raw
   `isNotBlank()` gate, so the condition is dropped and a rule whose only condition is that still matches
   every SMS. Same hazard class as the fixed defect, but pre-existing and already asserted by
   `AddonRulesTest.blankKeywordStillMatchesAnything`. Plan S3 ("zero-condition rules inert") is the real
   fix. Impact: such a rule fires `block_sender` / `forward` / autoreply on every message.
2. **`squash()` false positives** (cases 17/18): multi-digit keywords match across space boundaries.
   Deliberate trade-off for spaced-out evasion; consider a length gate or document it when S2 lands.
3. **`matchAll` removed from the model and the JSON** (`AddonRules.kt:20-42`, `load`/`save`). Old saved
   rules with `"matchAll": false` now load and silently behave as AND (every condition must hold) —
   unknown key ignored, no migration, no warning. Outside the S1 spec ("keep fields as-is, no schema
   change yet") and outside the normalization surface tested here, but it is a user-visible behaviour
   change for existing rules. Needs a release note or migration.
4. Sender matching normalizes but does not squash (documented, tested): `912345` does not match
   `912 345` while the equivalent keyword does. Asymmetric, unchanged from pre-S1.
5. Unit/JVM surface only; no device run. `execute()` actions (clipboard, forward, notification) are out of
   S1 scope and untested. `Normalizer` must stay in sync with `tools/mirror.py:normalize()`; drift is not
   covered by these tests.
6. Time-window tests depend on the host default timezone via `Calendar.getInstance()`; they pass here.
