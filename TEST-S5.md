# TEST-S5 — decision trace, per-rule dry-run, cooldown clamp, honest send

**Verdict: PASS**, with three cosmetic/robustness findings that nobody asked to fix and none of which can
throw into the receive path or lose an SMS.

- Commit under test: `136d0f2` "Addon S5: decision trace, per-rule dry-run, cooldown clamp, honest send".
- Suite: `:app:testDebugUnitTest` → **202 tests, 0 failures, 0 skipped** (S4 baseline was 184).
  Per class: `AddonRulesAdversarialTest` **108** (+18 new S5 tests, all mine), `AddonRulesTest` 62 (DEV's 7 S5 tests),
  `InboxPolicyTest` 18, `GoldenTest` 8, `ComposeIntentTest` 6.
- Build: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk $HOME/tools/gradle/gradle-8.9/bin/gradle --no-daemon :app:testDebugUnitTest`
- Files touched by TEST: `app/src/test/java/ir/smsfilter/addon/AddonRulesAdversarialTest.kt`, this file. **No main-source change.**

Findings, ranked:

| # | Finding | Severity | Gate |
|---|---|---|---|
| F1 | 40-char body cut is by UTF-16 unit, so an emoji straddling index 40 leaves a lone high surrogate (`U+D83D`) in the pref and in the Features list. No crash, one replacement glyph. | cosmetic | item 1 (surrogate safety) — **FAIL, cosmetic** |
| F2 | A corrupted `addon_decisions` value is never repaired: `recordDecision` parses the old array first, so every later write fails silently and the trace stays dead until Clear. | low | item 2 — survives, fails safe, no self-heal |
| F3 | When one rule fires and another is cooling, the line reports only what ran; the `cooldown` reason is dropped (`ran` non-empty wins in `decisionLine`). | cosmetic (display honesty) | item 1 (labelling) — partial |

Everything else asked for holds, including every crash-safety and clamping claim.

## 1. Case table

Test file: `AddonRulesAdversarialTest.kt`, section `S5`.

| # | Property | Test | Result |
|---|---|---|---|
| 1.1 | exactly 20 entries, reached without loss, then 5 more → still 20, newest first, oldest dropped | `s5TraceCapsAtExactlyTwentyNewestFirst` | PASS |
| 1.2 | 3 matching rules on 1 SMS → **one** line naming all 3; 21 SMS × 3 rules → 20 lines | `s5OneEntryPerSmsEvenWhenThreeRulesMatch` | PASS |
| 1.3 | miss names the condition: `keyword`, `sender`, `length`, `otp`, `disabled`, `no conditions` | `s5MissNamesTheConditionThatMissed` | PASS |
| 1.4 | `cooldown` label for a blocked rule (and it did not run); `nothing (action failed)` for a rule whose action did nothing | `s5CooldownBlockedAndActionFailedAreLabelled` | PASS |
| 1.5 | fire + cooldown in one SMS → only `copy_body` reported, `cooldown` lost | `s5MixedFireAndCooldownLineReportsOnlyWhatRan` | F3, recorded |
| 1.6 | Clear removes the key (not blanked), next SMS records again | `s5ClearEmptiesTheTraceAndTheNextSmsStartsFresh` | PASS |
| 1.7 | Persian body → prefix is exactly 40 code units, equals `body.take(40)`, survives the JSON round trip; `\n` flattened | `s5BodyPrefixIsFortyCharsForPersianAndNeverThrows` | PASS |
| 1.8 | emoji at index 39/40 → lone high surrogate, no crash, still persists | `s5EmojiAtTheCutLeavesALoneHighSurrogate` | F1 |
| 2.1 | prefs cannot even be opened → `dispatch` returns, no action runs (fail closed) | `s5DispatchSurvivesPrefsThatCannotOpen` | PASS |
| 2.2 | rule set cannot be read (every `getString` throws) → `dispatch` returns, legacy actions skipped, trace empty | `s5DispatchSurvivesARuleSetThatCannotBeRead` | PASS |
| 2.3 | trace write throws → legacy action **still ran**, nothing half-written | `s5TraceWriteFailureDoesNotStopTheActions` | PASS |
| 2.4 | corrupted trace value → reads as empty, no self-heal | `s5CorruptTraceValueDisablesTheTraceUntilClear` | F2, recorded |
| 4.1 | 17-input clamp table, every result in `0..86400` | `s5ClampCooldownTable` | PASS |
| 5.1 | exhaustive sweep of the thread window: 499 999 distinct ids in `1 000 001..1 499 999`, hash path reaches the floor, 500k aliasing documented | `s5AddonIdsOccupySixDigitsAboveTheSmsIds` | PASS |
| 5.2 | realistic sender hashes all outside the window; one concrete colliding number reproduced | `s5CollisionNeedsASenderHashInsideTheWindow` | PASS (residual case named) |
| 6.1 | autoreply: `execute` false on refused send, `executeAll` 0, cooldown **not** armed, next SMS retries, trace says `action failed`; true on success, then armed and blocked | `s5FailedAutoreplyIsNotSuccessAndDoesNotArm` | PASS |
| 6.2 | forward: same, and the full body goes to `actionParam` | `s5FailedForwardIsNotSuccessAndDoesNotArm` | PASS |
| 6.3 | guard-outs (spam autoreply, blank forward target) never call the seam and are not success | `s5GuardThatNeverCallsSendIsNotAFailedSend` | PASS |

Harness note: `FaultPrefs(mode)` is new in the test file — `FakePrefs` behind a proxied `SharedPreferences` with one
injected fault (`open` / `read` / `write`, the last one failing only for the `addon_decisions` key so rules and
cooldowns keep working). That is what makes gate 2 an observation instead of a code-read.

## 2. Privacy statement — what the trace writes, plainly

Stored, per SMS, one line, in **SharedPreferences file `smsfilter`** (app-private `/data/data/ir.smsfilter/shared_prefs/smsfilter.xml`, `MODE_PRIVATE`, unencrypted), key **`addon_decisions`**, JSON array of at most 20 strings:

`HH:mm · <full sender address> · <first 40 UTF-16 units of the message body, newlines → space> → <rule names>: <actions that ran | nothing (reason)>`

- **Message body: yes, first 40 characters of every SMS** (not only matches — the "no rule" path writes the same prefix).
- Sender: full address, untruncated. Time: minute resolution. Rule names, action names, miss reason.
- **Not** stored: full body, thread id, spam/contact flags, OTP field. An OTP that sits in the first 40 chars of the body is of course in the prefix.
- Lifetime: 20 messages, then overwritten; survives process restart and reboot; removed only by **Clear** or app data wipe.

Acceptable? **Yes, consistent with the app's existing posture, but it is a real widening.** The app already stores every
full message body in the SMS provider and already puts a full body into prefs (`IncomingSms.kt:148`, digest `last`), so
prefixes are not a new class of data. What is new: (a) the last 20 bodies are now retained *in prefs*, not just in the
system DB, and (b) `android:allowBackup="true"` (`AndroidManifest.xml:15`) means those prefixes travel in ADB/cloud backup
along with the rest of the prefs. Nothing unexpected is written — I found no path, IP, OTP-only field, stack trace or
device identifier in the line. The only unexpected-ish content is F1's lone surrogate, which is a truncation artifact, not
extra data. If prefs-level body retention is unwanted, the cheap fix is to store a 40-char prefix of the *normalized*
body or a redacted form; the trace's diagnostic value does not depend on the text.

## 3. `clampCooldown` — measured table

`text.filter { it.isDigit() }.take(9).toIntOrNull()?.coerceAtMost(86_400) ?: 0`

| input | output | note |
|---|---|---|
| `""` | 0 | no digits → 0 = "every time" |
| `"abc"` | 0 | |
| `"آ"` | 0 | non-digit letter |
| `"-5"` | 5 | sign dropped, never negative |
| `"0"` | 0 | keeps its documented "every time" meaning |
| `"30"` | 30 | |
| `"  30  "` | 30 | spaces dropped |
| `"86400"` | 86 400 | == `MAX_COOLDOWN` |
| `"86401"` | 86 400 | clamped |
| `"999999999"` | 86 400 | 9 digits is the widest that survives `take(9)` and still fits Int |
| `"999999999999"` | 86 400 | the old 11-digit → 0 overflow, now clamped |
| `"9007199254740993"` | 86 400 | 2^53+1 |
| `"2147483648"` | 86 400 | Int.MAX+1 |
| `"8 6 4 0 1"` | 86 400 | |
| `"۶۰"` | 60 | Persian digits |
| `"۱۲۳۴"` | 1234 | Persian digits |
| `"٩٩٩٩٩٩٩٩٩"` | 86 400 | Arabic-Indic digits |

No input in the table yields 0 from a numeric string, none yields a negative, none overflows: `take(9)` bounds the value
at 999 999 999 < `Int.MAX_VALUE` *before* `toIntOrNull()`, so the parse can never fail on size and the clamp is the only
thing that can reduce it. Residual: a Persian-digit string of 9 digits is parsed correctly because
`Integer.parseInt` accepts any Unicode decimal digit.

## 4. Notification id spacing — arithmetic

- SMS notification: `2000 + address.hashCode()` (`IncomingSms.kt:127`), filter notification `ADS_ID = 2001`.
- Addon notification: `1_000_000 + key`, `key = threadId.rem(500_000)` when `threadId > 0`, else `(sender.hashCode() and 0x7FFFFFFF) % 500_000` — so **addon ids ∈ [1 000 000, 1 499 999]** (swept exhaustively: 499 999 distinct thread ids, min 1 000 001, max 1 499 999; the hash path can also produce the floor 1 000 000).

`2000 + h` falls in that window iff `h ∈ [998 000, 1 497 999]` — a 500 000-wide band of 2^32 ≈ **0.0116 % of possible
sender hashes** (measured 0.0095 % over 200 000 random `09xxxxxxxxx` numbers, i.e. ~1 in 10 500 phone numbers).

Sampled realistic senders — none collide: `09121234567` → 1 922 729 178, `09351234567` → −758 716 039,
`+989121234567`, `1000` → 1 509 423, `Bank` → 2 064 940, `AD` → 4 083, `90001234`, `123`.

**Residual collision case, with a live repro:** `"09857893333".hashCode() == 1 283 250`, so its SMS notification is
`1 285 250`; an addon alert for `threadId = 285 250` (`1 000 000 + 285 250`) is the **same id**. Collision therefore
requires (a) a sender whose address hash lands in the 500k band and (b) `threadId ≡ hash − 998 000 (mod 500 000)` — the
thread condition is what makes it vanishingly rare in practice, but it is real and not hypothetical. Two smaller cases
inside the addon space: `threadId` and `threadId + 500 000` share an id, and two senders with equal `hash % 500 000` share
one (only reachable with `threadId == 0`). All three are the "shortcut" comment at `AddonRules.kt:519-520`, and all three
only replace one notification with another — no SMS is lost.

## 5. Send seam honesty

`internal var send: (Context, String, String) -> Boolean` is the only seam; both `autoreply` and `forward` return its
result directly (`AddonRules.kt:341,344`), so:

| case | `execute` | `executeAll` | cooldown | trace |
|---|---|---|---|---|
| autoreply, send → false | false | 0 | **not armed**, next SMS retries | `nothing (action failed)` |
| autoreply, send → true | true | 1 | armed, blocked inside the window | `autoreply` |
| forward, send → false | false | 0 | **not armed** | `nothing (action failed)` |
| forward, send → true | true | 1 | armed | `forward` |
| spam autoreply / blank forward target | false | 0 | not armed | seam never called |

Argument order is correct: autoreply sends `(sender, template)`, forward sends `(actionParam, body)`. `markFired` is only
reached from `dispatch` when `executeAll > 0`, so a refused send cannot swallow the retry window. `execute()` reads
`rule.action`, `executeAll` reads `actions` — a multi-action rule with `autoreply` in the list gets the same honesty
(the seam is inside `execute`, per action).

## 6. UNVERIFIED

- **Compose rendering** of the Recent-decisions list (collapsible, Clear button, `Test` dialog using `missReason`) — JVM
  tests never compose. Code-read only: `MainActivity.kt:963-1009` (list), `1022-1040` (dry-run dialog). No Compose UI test
  exists for the Features page; `ComposeIntentTest` covers other screens.
- **Process-restart persistence** — `FakePrefs` is in-memory, so "survives a restart" is `apply()` + XML semantics, not an
  observed restart. Same for the trace's survival across app kill.
- **Real `SmsManager`** — `Sms.send` is never called in tests; the seam proves the *plumbing*, not that the radio accepts
  the message. A refused send on a real device (no SEND_SMS grant, no SIM, carrier reject) is exactly what the seam models,
  but the mapping from `Sms.send`'s real return value to `false` is code-read (`Sms.kt`), not observed.
- **Real `NotificationManager`** — `addonNotifyId` arithmetic is verified; the actual `notify()` (and therefore whether an
  id collision really replaces a notification) is not.
- **Real SharedPreferences write of a lone surrogate** — the in-memory fake round-trips it untouched; the XML/UTF-8 writer
  on device will most likely emit `?`/U+FFFD. Not observed.
- **`clampCooldown` wiring** — `MainActivity.kt:1219` is the only caller; the editor's keyboard/input filter (whether a
  user can even type `-` or Persian digits) was not exercised.

## 7. Residual risks

1. **F1 — surrogate split at the 40-char cut.** Cosmetic, no crash, no data loss; visible as one `�` in the Features list.
   Fix is one line if wanted: `body.take(40).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }`.
2. **F2 — no trace self-heal.** One truncated/interrupted write (`apply()` racing a kill) leaves `addon_decisions`
   unparseable, and every later `recordDecision` fails silently (it re-parses the old value first). The trace stays dead
   until the user presses Clear. Cheap fix: on parse failure, start a fresh array instead of aborting.
3. **F3 — mixed fire+cooldown loses the reason.** `decisionLine` prefers `ran` over `miss`, so "one rule ran, another was
   cooling" reads as a plain success. Diagnostic-only.
4. **Notification id band.** 0.0116 % of sender hashes can land in the addon window; the concrete repro is
   `09857893333` + `threadId 285 250`. Consequence is one notification replacing another, never a lost SMS. A persisted
   per-sender id map is the documented upgrade path.
5. **Trace cost per SMS.** One JSON parse + one `apply()` of ≤20 short strings inside `dispatch`; not measured separately,
   but the S3 budget test (20 regex rules < 100 ms) has this inside it and stayed far under.
6. **Privacy widening (see §2).** Last 20 bodies' prefixes now persist in prefs and go into `allowBackup` backups. Matches
   existing practice (full bodies already in the SMS DB and in the digest `last` pref), so accepted — but it is a widening.
7. **Dry-run dialog** is unverified visually (§6); it is the one S5 surface with zero test coverage beyond
   `missReason` itself, which `s5MissNamesTheConditionThatMissed` does cover.
