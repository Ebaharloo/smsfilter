# TEST-S6 — final gate: F1/F2/F3 verification, suite, S6 feasibility review

**Verdict: PASS** (independent test pass; DEV code not edited, only the frozen fixture's stale assertions were flipped)

- Repo `~/coding/kotlin/smsfilter`, tree at `5f664c4` + DEV's uncommitted S6 fix (`AddonRules.kt`, `SmsAction.kt`).
- Suite: `:app:testDebugUnitTest` → **208 tests, 0 failures, 0 errors, 0 skipped**.
- Fixture integrity: `AddonRulesAdversarialTest.kt` was **unmodified by DEV** (`git diff` empty, 108 `@Test` before and after my flips) and its mtime (23:29:51) predates DEV's own fix test (`AddonTraceFixTest.kt`, 23:35:41). I am the only author of the diff in that file.
- Baseline before my edits, measured on this tree: **206 tests, 2 failed** — exactly `s5EmojiAtTheCutLeavesALoneHighSurrogate` (`.kt:1467`) and `s5CorruptTraceValueDisablesTheTraceUntilClear` (`.kt:1525`). Nothing else broke.

## 1. Fixture flips (3, not 2)

| old | new | why |
|---|---|---|
| `s5EmojiAtTheCutLeavesALoneHighSurrogate` | `s6EmojiAtTheCutIsKeptWhole` | Pinned the UTF-16-unit cut. F1 makes the cut code-point based, so the emoji at the boundary is now kept whole. New assertions: 41 UTF-16 units / 40 code points, prefix ends with the pair, last unit is a low surrogate, no unpaired surrogate in the line, pair intact after the JSON round trip. |
| `s5CorruptTraceValueDisablesTheTraceUntilClear` | `s6CorruptTraceValueSelfHealsOnNextRecord` | Pinned "no self-heal". F2 makes `recordDecision` fall back to a fresh array, so the *next* line is recorded without a Clear. Now loops four corrupt shapes (garbage, JSON object, truncated array, bare string), asserts heal + newest-first + Clear + the 20-line cap. |
| `s5MixedFireAndCooldownLineReportsOnlyWhatRan` | `s6MixedFireAndCoolingNamesBothRules` (+ new `s6CoolingOnlyLineStillSaysCooldown`) | **Did not fail, but is stale spec**: it asserted `assertFalse(line.contains("cooldown"))`, which passes only because the new text says `cooling:`. Left alone it would have "verified" the F3 bug forever. Now asserts `cooling: Cool` is named, the fired rule is not listed as cooling, and nothing ran twice. |

Flipping is not weakening: every old assertion that encoded a defect is replaced by a strictly stronger one (the emoji case now also round-trips through prefs; the corrupt case covers four shapes instead of one; the mixed case now names the blocked rule).

## 2. F1 — code-point-safe 40-char truncation

`head40` = `substring(0, offsetByCodePoints(0, min(40, codePointCount)))`. Verified through `decisionLine` (the only producer of trace lines) plus a prefs round trip:

| case | body | kept prefix | units | code points | lone surrogate |
|---|---|---|---|---|---|
| emoji at the cut | `"a"×39 + 😀 + "tail"` | `a×39 + 😀` | 41 | 40 | none |
| emoji before the cut | `"a"×30 + 😀 + "b"×30` | `a×30 + 😀 + b×9` | 41 | 40 | none |
| emoji after the cut | `"a"×40 + 😀 + "b"` | `a×40` | 40 | 40 | none |
| emoji first | `😀 + "a"×60` | `😀 + a×39` | 41 | 40 | none |
| 4-byte code point at the cut (U+1D11E 𝄞) | `"a"×39 + 𝄞 + "tail"` | `a×39 + 𝄞` | 41 | 40 | none |
| only emoji | `😀×60` | `😀×40` | 80 | 40 | none |
| only astral | `𝄞×41` | `𝄞×40` | 80 | 40 | none |
| empty | `""` | `""` | 0 | 0 | none |
| Persian regression (existing test) | Persian text | 40 units = 40 code points | 40 | 40 | none |

No crash on any shape; every prefix is a true code-point prefix of the body (`body.startsWith(prefix)`), so a pair is never half-kept. A lone surrogate already present in the *input* is preserved as-is (1 code point) — that is the sender's text, not a truncation artifact, and it cannot be "fixed" without inventing characters.

## 3. F2 — corrupted trace pref self-heals

| stored value | `recentDecisions` before | after one `recordDecision("next")` | after `"second"` | Clear |
|---|---|---|---|---|
| `[not json` | `[]` (no throw) | `["next"]` | `["second","next"]` | key removed |
| `{"a":1}` | `[]` | `["next"]` | `["second","next"]` | key removed |
| `["a","b"` (truncated array) | `[]` | `["next"]` | `["second","next"]` | key removed |
| `"just a string"` | `[]` | `["next"]` | `["second","next"]` | key removed |
| `garbage`, then 25 records | `[]` | — | capped at `DEC_MAX` = 20, newest first (`line24`) | — |

The fix is inside the existing `runCatching`, so a write that still fails (prefs unusable) stays contained — `s5TraceWriteFailureDoesNotStopTheActions` and `s5DispatchSurvivesPrefsThatCannotOpen` still pass.

## 4. F3 — fire + cooling in one SMS

| case | line produced | verdict |
|---|---|---|
| `Cool` cooling (3600 s), `Hot` fires, same SMS | `23:41 · 123 · کد 42 → Cool + Hot: copy_body; cooling: Cool` | PASS — both rules named, action reported, blocked rule named |
| cooling only | `23:42 · 123 · کد 42 → Cool: nothing (cooldown); cooling: Cool` | PASS — still says `cooldown`, nothing ran |
| cooling rule alone with no other match (`s5CooldownBlockedAndActionFailedAreLabelled`) | contains `cooldown`, not `copy_body` | PASS, unchanged |

The `; cooling: …` suffix is appended only when the list is non-empty, so every pre-existing line format (legacy, `no rule`, `nothing (…)`) is byte-identical — that is why only the two pinned tests moved.

## 5. Suite + gate numbers

```
ir.smsfilter.ComposeIntentTest                      tests=  6 fail=0
ir.smsfilter.GoldenTest                             tests=  8 fail=0
ir.smsfilter.InboxPolicyTest                        tests= 18 fail=0
ir.smsfilter.addon.AddonRulesAdversarialTest        tests=110 fail=0   (108 + 2 net new)
ir.smsfilter.addon.AddonRulesTest                   tests= 62 fail=0
ir.smsfilter.addon.AddonTraceFixTest                tests=  4 fail=0   (DEV's own fix test)
TOTAL                                               tests=208 failures=0 errors=0 skipped=0
```

`./scripts/gate.sh` with `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` (the bare `./scripts/gate.sh` dies with `What went wrong: 25.0.4.1` because the shell exports JDK 25 — known trap, not a regression):

```
== 1. unit rules   BUILD SUCCESSFUL (208 tests, 0 failures)
== 2. filter power
PASS  Gheychi   spam recall 0.891 (min 0.85)  important kept 0.969 (min 0.96)  wrongly hidden 79
PASS  PSSC      spam recall 0.896 (min 0.85)  important kept 0.979 (min 0.96)  wrongly hidden 47
PASS  my_inbox  spam recall 0.973 (min 0.95)  important kept 1.000 (min 0.92)  wrongly hidden 0
filter power unchanged or better
== 3. build       BUILD SUCCESSFUL in 2m 49s
APK: app/build/outputs/apk/release/app-release.apk (1714502 bytes)
```

Filter power clears every minimum on all three corpora with the same numbers as the previous pass; the addon fix cannot reach the classifier (default-off, no core import) and the numbers confirm it did not. No install was attempted (S6 ships no behaviour the phone can show beyond the Features list text).

## 6. Challenge to the S7–S10 promotions

All four are additive `AddonRule` fields, JVM-testable, and touch no core-filter code — those three criteria hold for all of them. LOC estimates (12–15) are plausible for the logic; none includes editor UI, which is where two of them actually live or die.

| item | ≤80 LOC | JVM-testable | no core change | useful for Persian SMS | my verdict |
|---|---|---|---|---|---|
| S7 `-term` negation | yes (12) | yes | yes | **yes** — `لغو11` is in ~60% of ads and OTP/marketing share `کد` | **agree, keep first** |
| S8 whole-word | yes (15) | yes | yes | yes, but the proposed regex is wrong | **agree with the goal, the change as written is broken — see below** |
| S9 `keywordScope` | yes (15) | yes | yes | marginal | **disagree with the stated reason; keep but last, re-justified** |
| S10 `disabledUntil` | yes (12) | yes | yes | yes, if it is settable | **disagree as planned: the field without a UI is a second `enabled` flag** |

### S8 — the proposed whole-word regex does not work on Persian (blocking)

Plan says: `Regex("(\\W|^)" + Pattern.quote(kw) + "(\\W|$)")`. In Java/Kotlin `\w` is ASCII-only (`[a-zA-Z_0-9]`) unless `UNICODE_CHARACTER_CLASS` is set, so **Persian letters are `\W`** and the boundaries match anywhere inside a word. Measured with `javac`/`java` 17 on this machine:

```
رمزنگاری   -> true      // must be false
رمز: 1234  -> true      // must be true
UNICODE رمزنگاری -> false
UNICODE رمز: 1234 -> true
```

So the plan's own first test (`رمز` whole-word must not match `رمزنگاری`) **fails against the plan's own code**. Fix is one flag: `Pattern.UNICODE_CHARACTER_CLASS` (or prefix `(?U)`), or use `(?<![\p{L}\p{M}\p{N}])…(?![\p{L}\p{M}\p{N}])`. ~same LOC.

Second defect in the same bullet: "ZWNJ/spaced evasion (`ر م ز`) still matches through `squash`". `Normalizer.squash` is `replace(" ", "")`, i.e. it deletes exactly the boundaries a whole-word check needs — after squashing, `سلام ر م ز 1234` becomes `سلامرمز1234` and the boundary test fails. Word mode and squash are mutually exclusive; the plan must state that word mode drops the squash path (and the test must assert that, not the opposite). Both are cheap to fix, but S8 must not be implemented from the plan text as written.

### S9 — the justification is factually wrong

Plan says "sender is a bank, body says برداشت" needs two rules today. It does not: `conditions()` already ANDs `senderContains` with the keyword condition, so `senderContains = "BankMellat"` + `keywords = ["برداشت"]` is one rule with one action. What `keywordScope` actually adds is narrower: keyword-style matching (list, OR, regex, whole-word) against the *sender*, and `any` = a term that may appear in either field. Worth ~15 LOC, but it is the weakest of the four and its "why it matters" must be rewritten before it enters a step, otherwise DEV implements it for a problem that does not exist.

### S10 — the value is in the button, not the field

The pain quoted ("wrong for an hour, not forever; a full disable the user forgets to undo") is solved by a one-tap snooze, not by a `disabledUntil: Long`. The rule editor has no timestamp input, so as planned the field is only reachable by editing `addon_rules_json`. Either add the affordance (a `Snooze 1h / 8h / off` chip writing `now + h*3600_000`, ~5 LOC of UI) or park it. Also note `enabled=false` and `disabledUntil` overlap: `matching()` filters `!enabled` before anything else and `missReason` returns `"disabled"`, so a snoozed-and-disabled rule must not report `snoozed` — one extra ordering test.

### Priority
Agreed order S7 → S8 → S9 → S10 becomes S7 → S8 (fixed) → S10+chip → S9, on value per LOC for this app.

## 7. Parked rows — one wrongly parked, one contradiction

19 parked rows checked (`BUZZKILL-GAP.md:21,23,28,29,31–42,50,52,54`); 13 `done`, 4 `feasible now`, 19 `parked` — counts are correct (36 rows).

**Wrongly parked: the `Emoji` extra (`BUZZKILL-GAP.md:42`).** The park reason names `Category, Language, Image, GroupConversation` as the parts needing a notification payload or ML Kit — `Emoji` is in BuzzKill's extra list and needs neither. It is one regex over the body (`[\p{So}\p{Sk}]`, count `>= 2`), ~6 LOC, JVM-testable, and it is the strongest ad signal this project already trusts: the core classifier's strong-ad gate is literally "a link, 2+ emoji, or promo score >= unsure". A user rule "2+ emoji → archive" is exactly the kind of rule this app is for, and the addon has no emoji condition at all (`grep -rn emoji app/src/main/java/ir/smsfilter/addon/` → nothing). Same reasoning, weaker: `PhoneNumber` (body contains a number, reuses `InboxPolicy`'s phone regex, ~5 LOC) is also swept into a park reason that does not cover it.

**Contradiction, not a wrong park:** row 23 (`match modes 0/1/2`) is parked because "`keywordRegex` already expresses exact (`^…$`) and word mode", while row 25 (whole-word) is promoted as a `keywordWholeWord` boolean. Both cannot be the story. Promotion is defensible (a flag beats typing a regex and skips the 200-char regex subject cap), but the row-23 reason should be amended so the matrix does not argue against its own plan.

**Correctly parked, checked and agreed:** keyword tree (S7's `-term` covers the real need), package/app selection (no package concept), multi-range schedule (schema + editor, not ≤80 LOC), all 11 device-state conditions (permission/system service, not JVM-testable, irrelevant to an SMS-only filter), other stateful plugins, history/fire-counts (`HistoryDb` is a new store; a prefs counter would be ~6 LOC and is only worth it if the Features page ever shows "fired N times" — borderline, not a miss), notification listener (out of scope this pass).

## 8. What this pass does not prove

- **No device/emulator run.** All F1/F2/F3 evidence is JVM. The trace line is display-only (Features page), so a visual check of the emoji rendering is the only on-device item left; nothing in the fix touches the receive path's side effects.
- **Editor UI for the new trace text is untested** (Compose is not exercised by any test, as in S3–S5): the `; cooling:` suffix is asserted at the string level only.
- `AddonTraceFixTest.kt` is DEV's own file. I read it (4 tests, meaningful, no weakening of my fixtures) but it is not independent verification — the F1/F2/F3 verdicts above rest on my own flipped/new tests.
