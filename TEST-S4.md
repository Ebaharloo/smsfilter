# TEST-S4 (pass 2) — re-verification of the S4 fixes

**Verdict: PASS.** All four defects from pass 1 are closed in the working tree (uncommitted, on top of `9400dc7`).
The five fixture assertions from pass 1 that encoded the old no-op-counts-as-success behaviour were **my error** and are
rewritten here to encode real effects. Four action→success leaks remain that nobody has asked to close; one of them
(`autoreply`/`forward` ignoring `Sms.send`'s result) can still arm a cooldown after a failed send — see §5.

- Suite: `:app:testDebugUnitTest` → **177 tests, 0 failures, 0 skipped** (before this pass: 175 tests, **5 failed** — all five were the bad fixtures below).
  Per class: `AddonRulesAdversarialTest` 90, `AddonRulesTest` 55, `InboxPolicyTest` 18, `GoldenTest` 8, `ComposeIntentTest` 6.
- Build: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=$HOME/Android/Sdk $HOME/tools/gradle/gradle-8.9/bin/gradle --no-daemon :app:testDebugUnitTest`
- Test-only edits: `AddonRulesAdversarialTest.kt`, `AddonRulesTest.kt`, this file. **No main-source file touched by TEST.**

## 1. Defect closure

| # | Defect (pass 1) | Fix in tree | Evidence | State |
|---|---|---|---|---|
| D1 | `executeAll` counted "did not throw", so a rule of silent no-ops armed its cooldown | `execute()` returns `Boolean`; `executeAll` does `if (execute(...)) ok++`; `postAddonNotification` returns `false` when `nm == null`; unknown action falls to `else -> false`; `email` returns `false` when `startActivity` throws | code-read `AddonRules.kt:270-275, 279-395`; tests `s4NoOpActionDoesNotCountAsSuccessAndDoesNotArm`, `copyBodyOnSpamIsNotSuccess`, `copyOtpWithoutACodeIsNotSuccess`, `unknownActionIsNotSuccess`, `guardedOutActionsAreNotSuccess`, `alertWithoutANotificationManagerIsNotSuccess`, `cooldownArmsOnlyForAnActionThatRan` | **CLOSED** |
| D2 | addon alert posted under `event.notifyId`, replacing the SMS notification | `addonNotifyId(event) = 3000 + (threadId if > 0 else sender.hashCode())`; `postAddonNotification` notifies under it, never `event.notifyId`; `dismiss`/`modify_text` still cancel the real SMS id | code-read `AddonRules.kt:452-457, 468`; tests `alertNotificationIdIsNotTheSmsNotificationId` (≠ `event.notifyId`, same conversation reuses the id, other thread differs, sender fallback differs, two senders with no thread do not collide) | **CLOSED** (one id-space risk left, §6.1) |
| D3 | `dismiss`/`modify_text` on a message that was never posted under an id could cancel an older notification of the same sender | both no-op and return `false` when `notifyId <= 0`; `IncomingSms` builds the event with `notifyId` only for `Notify.NORMAL`, `0` for DIGEST/SILENT | code-read `AddonRules.kt:365-375`, `IncomingSms.kt:126-133`; test `notifyIdZeroSuppressesDismissAndModifyText` (+ new "must not arm" assertion) | **CLOSED** |
| D4 | channel creation and the three notify paths were outside any try/catch, so a failed `notify()` skipped `AddonBus.dispatch` | one `try { … } catch (Throwable)` around channel creation + `when(decision)`; `AddonBus.dispatch(ctx, event)` is a single unconditional statement after it | code-read `IncomingSms.kt:135-204`; the five failing fixtures only went green once dispatch became reachable on that path | **CLOSED** |

## 2. Fixture corrections (my pass-1 mistakes)

Pass 1 pinned `executeAll == 2` / "armed" for a rule made of silent no-ops and called that required behaviour. It was not:
the defect was real, the assertion was backwards. Changes:

| Test | Was | Now | Why |
|---|---|---|---|
| `s4NoOpActionCountsAsSuccessAndArms` | `assertEquals(2, executeAll)`; asserted `addon_fired_noop` written | renamed **`s4NoOpActionDoesNotCountAsSuccessAndDoesNotArm`**: `assertEquals(0, …)`, `assertFalse(armed)`, `assertTrue(allowedNow)` | real effect: `copy_body` on spam and an unknown action must not arm |
| `s4PartialSuccessArmsTheCooldown` | `mark_read` with `threadId 0` (a no-op) was the "success" | `actions = ["copy_body", "copy_otp"]`, event with no OTP → `copy_body` really copies (count `1`), `copy_otp` is the silent no-op | one real action beside one no-op is the actual partial case |
| `s4FailedRuleDoesNotStopTheNextRuleFromArming` | `mark_read` as the "good" rule (also a no-op at `threadId 0`) | `bad = copy_otp` (no OTP), `good = copy_body` | proves the *next* rule really fires after a failed one |
| `s3DispatchExecutesEveryMatchingRuleExactlyOnceInPriorityOrder` | all rules fired `copy_otp` on an event with `otp = null` → every rule "succeeded" by doing nothing | `evT(...)` carries `otp = "1234"` and the context answers the clipboard, so each rule really copies | order/count evidence must come from real fires, not no-op accounting |
| `s3CoolingRuleIsSkippedButLaterRulesStillRun` | same | same (`otp` set, clipboard context) | the cooling rule must be the only thing skipped |

New helper: `private inner class ClipPrefs` in `AddonRulesAdversarialTest` — `FakePrefs` + a `ContextWrapper` that answers
`CLIPBOARD_SERVICE` with a reflectively built `ClipboardManager` (same trick `AddonRulesTest.FakeClipPrefs` uses; the stub
constructor in `android.jar` is the only way to get one on the JVM). Without it, "success" and "no-op" are
indistinguishable in that file — which is how the bad assertions got written.

Added to `AddonRulesTest` (DEV's new file, assertions only): `guardedOutActionsAreNotSuccess` (blank forward target, blank
sender, blank template, `threadId 0` for `mark_read`/`archive`/`pin`/`mute`), `alertWithoutANotificationManagerIsNotSuccess`,
the two-sender id check, and "no-op dismiss must not arm".

No test deleted; no other test's intent changed.

## 3. Action → success audit

"true when" is what the code guarantees; the last column is what it does **not** check.

| action | returns true when | remaining silent success |
|---|---|---|
| `copy_otp` | `otp != null` and a `ClipboardManager` exists | none (the clipboard write is the effect; a failed Toast is swallowed on purpose in `safeToast`) |
| `copy_body` | `!isSpam` and a `ClipboardManager` exists | blank body copies `""` (trivial) |
| `autoreply` | `!isSpam && fromContact && sender.isNotBlank()` and the template (param, else pref default) is non-blank | **`Sms.send(...)`'s `Boolean` is ignored** → true when the send fails (no default SMS sub, `statusIntent` null, provider refusal) |
| `forward` | `actionParam.isNotBlank()` | same ignored `Sms.send` result; also forwards spam bodies (pre-existing, intentional) |
| `mark_read` | `threadId > 0` | `Sms.markThreadRead` is `Unit` and swallows the provider exception → true when the update was rejected |
| `mute` / `unmute` | `threadId > 0` **and** the flag actually changes | none (prefs) |
| `pin` / `unpin` | `threadId > 0` and the flag actually changes | none (prefs) |
| `archive` / `unarchive` | `threadId > 0` | **`ThreadFlags.setArchived`'s `Boolean` (provider update) is discarded** → true when MIUI refuses (the local marker is written anyway) |
| `quarantine` / `restore` | `threadId > 0` | `Unit` wrappers around archive + mark-read: both failures invisible |
| `block_sender` | `sender.isNotBlank()` | prefs write only — effectively none |
| `dismiss` | `notifyId > 0` | `nm` null → nothing cancelled, still true (impossible on a real device) |
| `modify_text` | `notifyId > 0` | **`postAddonNotification`'s `Boolean` is discarded** → true when `nm` is null and nothing was posted (the one leak D1's fix missed) |
| `alert` | `postAddonNotification` returned true (`nm != null`, `notify()` did not throw) | channel muted by the user → still true (unobservable, acceptable) |
| `email` | `to` non-blank and `startActivity` did not throw | chooser opened but user cancels → true (the app did its part) |
| *(unknown name)* | never | fixed (was true) |
| *(dryRun)* | always true | by design (`FeatureSelfTest`), documented in code |

Net: D1's stated scope (`alert` with null `nm`, guarded-out branches, `email` rethrow, unknown action, `dryRun`) is
correct. The four leaks left — the `Sms.send` result (`autoreply`, `forward`), `markThreadRead` being `Unit` (`mark_read`,
`quarantine`, `restore`), `setArchived`'s return value (`archive`/`unarchive`), and `modify_text` discarding the post
result — all predate S4 and none was in the S4 defect list. The one worth fixing is `autoreply`/`forward`: `Sms.send`
already returns `Boolean`, so it is `if (Sms.send(...)) true else false` (three sites including the two legacy
`SmsAction` copies if they are to stay consistent).

## 4. `IncomingSms.deliver` re-read (D4 + D3)

One hunk, `IncomingSms.kt:121-204`. Confirmed:

- Exactly **one** `AddonBus.dispatch` in `deliver` (line 204), unconditional, outside the `try/catch`, after channel
  creation and after every `notify()`.
- The digest and silent `return`s are gone; they are `when (decision)` branches. `Notify` has three values (`NORMAL`,
  `DIGEST`, `SILENT`), so `else ->` can only catch `NORMAL` — no state falls into a wrong branch.
- Digest behaviour identical: count read/increment/`last` write, `ADS_ID`, `ADS_CHANNEL`, `setOnlyAlertOnce`, `autoCancel`.
  Silent still posts nothing.
- **No change** to filtering, `Blocked`, `insertInbox`, `Store.add`, `threadIdFor`, mute/contact lookups, digest counting,
  or the early `return`s above line 121. The diff has no hunks in those regions.
- Behaviour changes I still see, all intended: (a) notify failures are logged instead of propagating, so a broken
  notification no longer costs the message its addon rules; (b) the event carries `notifyId = 0` on DIGEST/SILENT, so a
  `dismiss`/`modify_text` rule no longer cancels a stale notification on those paths — a user rule written to "clean up
  ads" that way silently stops working there; (c) channel creation is now inside the `try`, so a first-ever message whose
  channel creation throws still runs rules (before, it crashed out first).

## 5. Remaining leaks — recommendation

`autoreply`/`forward` arming a cooldown after a failed send is the same class of bug as D1, narrower trigger (no default
SMS sub, provider refusal). Fixing it is a DEV decision and one line per site; it is **not** a blocker for S4, whose brief
was "no-ops must not arm". The `mark_read`/`archive` family needs `Sms`/`ThreadFlags` to report results first — larger,
out of scope.

## 6. Residual risks

1. **Notification-id spaces overlap.** SMS uses `2000 + address.hashCode()`, addons `3000 + threadId` (or `+ sender
   hash`). Both are unbounded ints, so an address whose hash is `1000 + threadId` collides: the alert then replaces that
   sender's SMS notification. Cosmetic, low probability; a high offset (e.g. `1_000_000`) would close it. Untouched here.
2. Addon alerts are per conversation: a second alert for the same thread replaces the first, and they never auto-clear.
3. `autoreply`/`forward` arm the cooldown even when `Sms.send` returned `false` (§3, §5).
4. `archive`/`unarchive`/`quarantine`/`restore` return true even when the provider rejected the update (MIUI does this).
5. `modify_text` returns true when `nm` is null; and the reposted notification loses the SMS one's Reply / Mark-read /
   copy-code actions (it is a plain `Notification.Builder`, not the `NotificationCompat` one).
6. `dryRun` reports success for every action, unknown names included — correct for `FeatureSelfTest`, useless as evidence.
7. Cooldown arming is "≥ 1 action really ran", not "all actions ran": `[copy_body, autoreply]` on a non-contact arms on
   the copy although no reply went out. That matches the requirement as written.

## 7. UNVERIFIED (no device, no emulator, no Robolectric)

- Real clipboard write and Toast; real `NotificationManager.notify`/`cancel` — including the **on-device** claim that an
  addon alert does not replace the SMS notification (only `addonNotifyId()` arithmetic is tested here).
- `IncomingSms.deliver` end to end: no test builds a real SMS `Intent`, so "digest/silent get `notifyId = 0`" and
  "exactly one dispatch per message" are code-read facts, not executed facts.
- `ThreadFlags` / `Sms` provider side effects (mute/pin/archive/quarantine/mark-read), the email chooser, `Sms.send`.
- Cooldown survival across a process restart on device (pref-based, should hold; not exercised).
- The whole rule-editor UI (unchanged by S4).
