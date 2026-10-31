# Handoff

## State
BuzzKill-style addon rebuilt from "spec port" into a working rule engine. Core filtering untouched.
Plan + evidence: `PLAN-buzzkill-fix.md`, `BUZZKILL-GAP.md`. Test reports: `TEST-S1..S6.md`.

## Done (committed)
- `585ebf2` S1 Persian normalization (Normalizer + squash) before matching; a term that normalizes to empty can never match.
- `c4daf44` S2 keyword list + ALL/ANY + regex, bounded (200-char subject, 120-char pattern, no nested/quantified groups, max 2 quantifiers). `regexUsable` is shared by matcher and editor, so a dead pattern cannot be saved silently.
- `6029d8f` S3 every matching rule runs in priority order, multi-action list, per-rule cooldown in prefs, zero-condition rule inert unless Any SMS.
- `9400dc7`+`f8128fe` S4 dispatch after the SMS notification is posted; action success is honest (a no-op does not arm cooldown); addon notifications use their own id band; digest/silent pass notifyId 0; a notify failure no longer skips rules.
- `136d0f2`+`5f664c4` S5 20-entry decision trace, Recent decisions list, per-rule dry-run Test; cooldown clamp; send-result honesty.
- `d6463e0` S6 trace fixes (code-point-safe, self-healing, cooling named), gap matrix feasibility, S7-S10 promoted.

## Verification
208 JVM tests, 0 failed. `scripts/gate.sh` green: Gheychi 0.891/0.969, PSSC 0.896/0.979, own inbox 0.973/1.000, 0 hidden; release APK builds.
No device run. UI and notification effects are code-read only.

## Next
1. S7 `-term` negation (~12 LOC) — bank OTP vs ad in one rule.
2. S8 whole-word — MUST set `Pattern.UNICODE_CHARACTER_CLASS`; ASCII `\W` breaks Persian (proved).
3. S10 snooze needs a UI chip, not just the field.
4. S9 keyword scope — sender+keywords already AND; keep last.
5. Emoji extra was wrongly parked (~6 LOC, strongest Persian ad signal).

## Device checks still owed
`dismiss` really removes, `modify_text` replacement survives, `alert` does not overwrite (brief flash expected), digest/silent paths still run rules, cooldown arms only on real work.

## Build
`scripts/gate.sh [--install]`; unit tests via `$HOME/tools/gradle/gradle-8.9/bin/gradle --no-daemon :app:testDebugUnitTest` with `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`.
