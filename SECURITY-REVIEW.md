# Security & sensitive-data review — smsfilter (pre-publication)

**Verdict: FIX-FIRST.** Two tracked test files contain real personal data (a real bank card number and the
author's real name, both taken from the developer's own SMS corpus). Everything else about the repo is clean:
no data corpus, no screenshots, no keystore, no network stack, no secrets.

Scope: repository content and git history only (`~/coding/kotlin/smsfilter`). `~/.hermes`, the
phone and `data/` contents (other than as a reference corpus for comparison) were not scanned.

Repo state at review: 91 tracked files, 59 commits `2026-10-22 … 2026-11-05`, branch `main` 24 commits ahead
of `origin/main` (32 commits), tags `v0.3`, `v0.3.3`, GitHub repo confirmed `PRIVATE`. Working tree clean
apart from one untracked PNG (`reply_blank_card_360dp.png`).

## Area-by-area result

| # | Area | Result | Evidence |
|---|------|--------|----------|
| 1 | Real SMS corpus (`data/`) in repo | PASS — never tracked | `.gitignore` has `data/`, `shots/`, `local.properties`; `git log --all --name-only \| grep -cE '^(data\|shots)/'` → `0`; `git ls-files` → 0 hits |
| 2 | Screenshots (`shots/`) | PASS — never tracked | same command → 0; `shots/*.xml` do contain real inbox/ads bodies but stay local |
| 3 | `local.properties` (`sdk.dir=~/…`) | PASS — untracked/ignored, never in history | `git ls-files \| grep local.properties` → empty |
| 4 | Phone numbers in tracked files | PASS with one note | all numbers are synthetic (`09123456789`, `0935…`, `9830001234`, masked `+989****4567`) except `09857893333` (see L5) |
| 5 | Email addresses in tracked files | PASS | `git grep -E '[A-Za-z0-9._%+-]+@…'` over tracked tree → 0 matches |
| 6 | Passwords / API keys / tokens / Bearer | PASS | `git grep -iE 'password\|secret\|api[_-]?key\|Bearer '` → only prose, the OTP lexicon `"password"` in `InboxPolicy.kt:144`, and `rules.json` keyword `"one time password"` |
| 7 | Keystore / signing material | PASS | no `*.jks`/`*.p12`/`debug.keystore` tracked or on disk; release uses the *local* debug key (`app/build.gradle.kts`), file lives in `~/.android` |
| 8 | `INTERNET` permission | PASS | `AndroidManifest.xml` permissions: POST_NOTIFICATIONS, READ_SMS, RECEIVE_SMS, SEND_SMS, RECEIVE_MMS, READ_PHONE_STATE, READ_CONTACTS + receiver/service permissions. No INTERNET, no ACCESS_NETWORK_STATE |
| 9 | Telemetry / analytics / crash reporting | PASS | dependencies are Compose/AndroidX/JUnit/org.json only; `git grep -iE 'firebase\|crashlytics\|analytics\|telemetry'` → 0 |
| 10 | SMTP / email action | PASS — no email action exists at all | actions are `otp_autocopy`, `copy_body`, `autoreply`, notify/alert/dismiss/modify_text; no `mailto:`, no SMTP, no `java.net` except `URLDecoder` in `ComposeIntent.kt:85` |
| 11 | Test fixtures vs real corpus | **FAIL — 2 files** | longest-common-substring diff of every Persian literal against `data/sms.jsonl` + `data/labeled.jsonl` found 3 matches in 2 files (L1, L2 below) |
| 12 | Docs / plan / handoff / CHANGELOG | PASS for personal data | only lexicon words (تخفیف، رمز، لغو11…), no message bodies, no names, no numbers |
| 13 | `~`, `.hermes` private paths | **FAIL (minor) — 7 tracked files** | L4 below |
| 14 | Commit author metadata | NOTE | 50/59 commits authored `e.baharloo <redacted>` → public on publish (L6) |
| 15 | Deleted-but-in-history files | PASS | 29 paths ever-existed-minus-tracked, all benign (old icons, superseded tests, `AGENTS.md`, 3 `tools/__pycache__/*.pyc`). 23 of them exist only in the **stash** (`ea13d1f`, `5060b95`), which is never pushed; `AGENTS.md` mentions `~/wiki`, `ContactsTest.kt` is synthetic. No `data/`, no `shots/`, no corpus content |
| 16 | Secrets in git pack (all 358 blobs) | PASS except above | full blob scan for `/home/`, emails, `0?9\d{9}`, `password\|secret\|api_key\|keystore`, dev name, card → only the findings below |
| 17 | `allowBackup` | DOCUMENT | `android:allowBackup="true"` and no `fullBackupContent`/`dataExtractionRules` → the quarantine DB + prefs (message bodies, senders) are included in Android/Google cloud backup. Not a leak, but the README's "Fully offline" claim should qualify it |

## Leaks in TRACKED files (reported, not fixed)

**L1 — real bank card number + real transaction, HIGH.**
`app/src/test/java/ir/smsfilter/InboxPolicyTest.kt:116` (and repeated at :121)

```
// the real Saman Bank body: it embeds LRE/PDF around the card number
val raw = "بانك سامان\nبرداشت مبلغ 1,234,567 انتقال وجه\nاز \u202A1111-111-1111111-1\u202C"
```

`1111-111-1111111-1` is verbatim from the developer's inbox (`data/sms.jsonl:20,30,53,67,90` — same card on
5+ Saman Bank transactions, with balances). Introduced in **`74b4faf`** (2026-10-22), present in
`origin/main` and both tags. This is the one that must not be published.

**L2 — author's real name in a real delivery SMS, MEDIUM.**
`app/src/test/java/ir/smsfilter/GoldenTest.kt:73`

```
val delivery = "کاربر نمونه عزیز!\nسفارشتون تا چند ساعت آینده به دستتون می‌رسه. …
```

"Ebrahim Baharloo" in a real DIGIKALA delivery message. Introduced in **`b73f23b`** (2026-10-23), present in
`origin/main` and both tags. (Surname is already implied by the GitHub handle `Ebaharloo`, so severity is
mostly the real-message provenance, not the name itself.)

**L3 — tracked Kotlin compiler stubs, HYGIENE (not a leak).**
Four `.kotlin/errors/errors-17926*.log` files (185 bytes each) are tracked; they contain only
`kotlin version` + an "already registered" message, no paths. `.kotlin/` is gitignored, so the 7 other,
larger logs on disk (including the ones that *do* contain `~/...`) are correctly ignored and
untracked. Committed in the first commit **`3a161b4`**; remove for tidiness only.

**L4 — private local paths and toolchain paths in docs/tools, LOW.**
`BUZZKILL-GAP.md:3,9`; `PLAN-buzzkill-fix.md:3,35,36,37,47`; `TEST-S1.md:80`; `TEST-S2.md:3`;
`tools/eval_real.py:8,22`; `tools/train.py:26` (`~`, `~/Downloads/Apps/...`,
`~/Documents/BuzzKill_reverse_engineering_spec.md`, `~/research/persian_sms`);
`scripts/avd.sh:25` (`~/.hermes/logs/emulator.log`). Username + machine layout + a reverse-engineering
workflow note. No credentials.

**L5 — `09857893333`, LOW/unverified.**
`TEST-S5.md:112,165` and `app/src/test/java/ir/smsfilter/addon/AddonRulesAdversarialTest.kt:1671,1685`.
A well-formed Iranian mobile used as the `hashCode()` collision repro. It does **not** appear in the corpus,
so it is probably invented — but it is indistinguishable from a real number; replace with an obviously fake
one if you touch the file.

**L6 — author email in commit metadata, NOTE.**
`git log --format='%an <%ae>' | sort | uniq -c` → `50 e.baharloo <redacted>`, `6 abraham <abraham@localhost>`.
Becomes public with the repo. Git history cannot be scrubbed of this without a rewrite (or GitHub
"keep my email private" only affects *future* web-UI commits, not existing author lines).

## History

- `git rev-list --all --count` → 59; range `2026-10-22 … 2026-11-05`; `origin/main` = `60e10af`, 32 commits;
  local `main` = `5b37aba`, 24 commits ahead, 0 behind (unpushed work is *more* than what is already on GitHub).
- Leaks L1/L2 are on **`origin/main` today**; L4 too. Nothing sensitive was introduced by the unpushed
  commits that is not already in the reviewed working tree.
- Every path ever touched: `git log --all --name-only --pretty=format: | sort -u` → 120 paths vs 91 tracked.
  The 29 extras are old icons, superseded test files, `scripts/feature_test.sh`, `tools/__pycache__/*.pyc`
  (contain only `~/...` strings) and `AGENTS.md` (mentions `~/wiki`). 23 of these live only in
  `refs/stash` (`ea13d1f`) and its untracked-files commit (`5060b95`); the stash is **not** pushed, so it does
  not reach GitHub — but do not `git push --mirror` or `git push origin stash@{0}`.
- Dangling commits `eae86cb`, `db598fb` (old stashes) are also local-only and contain no personal data.
- `data/` and `shots/` have never been committed in any ref, tag or stash.

## If you want a clean history

Two options — not executed, your call:

1. **Rewrite** with `git-filter-repo` (removes the two strings and the `.kotlin` logs from all 59 commits,
   keeps the 40+ commit narrative). Requires force-push of `main` + tags, and a re-clone by anyone who
   already has it. Also the only way to change the author email (L6).
2. **Squash / re-publish as one fresh commit** (new repo or `orphan` branch): fast, zero leakage of the old
   blobs, but you lose the commit history.

Either way, the fix itself is three edits: replace the card number in `InboxPolicyTest.kt:116,121` and the
name in `GoldenTest.kt:73` with synthetic values (e.g. `6037-9911-0000-0000`, `کاربر عزیز`), delete
`.kotlin/errors/*.log` from tracking, and optionally strip the `~` paths. A rewrite is mandatory
afterwards if you care that the old blobs are still fetchable — commit-purging only the working tree is not
enough once the repo is public.

## Is this safe to publish?

**No — not as-is: `InboxPolicyTest.kt:116` exposes a real bank card number that is already on `origin/main`.**
Fix L1 + L2 (and delete the tracked `.kotlin` logs), then either rewrite history or re-publish fresh;
after that the repo is safe to make public.
