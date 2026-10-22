# Feature matrix — SMS Filter messenger

Baseline: MIUI Messages, Samsung Messages, Google Messages, Apple Messages.
Legend: ✅ done · 🟡 partial · ⬜ missing

## Inbox / conversation list
| # | Feature | State |
|---|---------|-------|
| 1 | Photo avatars, name, snippet, time, unread badge | ✅ |
| 2 | Search conversations | ✅ |
| 3 | Pin / favourite conversation | ✅ |
| 4 | Archive conversation | ✅ |
| 5 | Mute conversation (no notification) | ✅ |
| 6 | Block sender | ⬜ |
| 7 | Delete conversation | ✅ |
| 8 | Mark read / unread manually | ✅ |
| 9 | Multi-select + bulk delete/archive | ✅ |
| 10 | Category tabs (All / OTP / Transactions / Ads) | 🟡 (Ads page only) |
| 11 | Swipe actions on rows | ✅ |

## Thread
| # | Feature | State |
|---|---------|-------|
| 12 | Bubbles, day separators, timestamps | ✅ |
| 13 | Send SMS | ✅ |
| 14 | Dual-SIM picker on send | ✅ |
| 15 | Delivery report / sent-failed state | ⬜ |
| 16 | Draft persistence | ⬜ |
| 17 | Long-press message: copy / forward / share / delete / details | ⬜ |
| 18 | Multi-select messages | ⬜ |
| 19 | Search inside thread | ✅ |
| 20 | Clickable links, tap-to-call numbers, OTP tap-to-copy | ⬜ |
| 21 | Attachments, MMS, group MMS | ⬜ (role accepts push, no download) |
| 22 | Scheduled send | ⬜ |
| 23 | Text size control | ✅ |

## Notifications
| # | Feature | State |
|---|---------|-------|
| 24 | Per-conversation notification | ✅ |
| 25 | Inline quick reply | ✅ |
| 26 | Mark-as-read from notification | ✅ |
| 27 | OTP copy button on notification | ✅ |
| 28 | One digest for filtered ads instead of silent drop | ✅ |

## Filter (our differentiator)
| # | Feature | State |
|---|---------|-------|
| 29 | Spam never written to inbox, kept in Ads folder | ✅ |
| 30 | Why-it-was-filtered shown per message | ✅ |
| 31 | Teach: "this is important" / "this is an ad" | ✅ |
| 32 | Allowlist / blocklist management page | ⬜ |
| 33 | Auto-delete ads after N days | ⬜ |
| 34 | Contact messages never content-filtered | ✅ |

## Settings / data
| # | Feature | State |
|---|---------|-------|
| 35 | Theme (auto/light/dark) | ✅ |
| 36 | Default SMS role request | ✅ |
| 37 | Backup / export messages | ⬜ |
| 38 | Per-conversation sound / vibrate | ⬜ |
| 39 | Block list page | ⬜ |

## Tiers
- **T1 (next):** 17, 20, 16, 25, 26, 27, 14, 8, 13 delivery status — the features you notice within five minutes of daily use.
- **T2:** 3, 4, 5, 6, 9, 11, 19, 28, 32, 33, 39.
- **T3:** 21 (MMS), 22, 37, 38, 10.

## Regression gate
`./scripts/gate.sh` runs, in order:
1. `:app:testDebugUnitTest` — pure inbox rules in `core/InboxPolicy` (archive visibility, pin order, search, mute/spam notify policy, OTP extraction, link/number detection).
2. `tools/gate_eval.py` — filter power on Gheychi, PSSC and your own labeled inbox; exits non-zero if spam recall drops below 0.85 (0.95 on your inbox) or important messages get hidden.
3. `:app:assembleDebug`.

Nothing merges unless all three pass. `./scripts/gate.sh --install` also pushes to the phone.
