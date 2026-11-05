# TEST-UX — pass 2: fix verification + what is still wrong

Reviewed 2026-11-05, Waydroid Android 13, `192.168.240.112:5555`, installed build **v0.3.13 (16)** = commit `f67d72d`.
Method: driven only with `input tap` / `input text` / `keyevent` / `swipe`; every claim has a `uiautomator dump` bound, a pixel measurement, or a screenshot. No code was edited, nothing installed.
Standard: `material-design-3-ui` (component semantics, tokens, states, accessibility, adaptive).

Window 2560×1396 px @ density 180 → **2275×1241 dp** (desktop-class). A 1080-px-tall window (1707×960 dp) was used once for the short-window test; `wm size reset` afterwards, `font_scale` back to 1.0, app text size back to Normal.

## Verdict

**Ship with the remaining fixes listed below — the five blockers are genuinely gone, but the accessibility gate is still red.**

- All 11 claimed fixes verified by tap path. 9 pass outright; M1 is half-done (note added, disabled signal still weak) and M2 splits terms but still has no term model.
- Nothing regressed in the addon's *logic*. One layout regression arrived with the reorder fix: rule rows are now `fillMaxWidth` + `SpaceBetween`, so on a wide window the ↑/↓ sit **1885 dp away** from `Edit/Test/Delete` (measured: actions end x=285 px, arrows start x=2406 px).
- Not shippable *as a tablet/desktop or low-vision experience*: system font scale is still discarded (`font_scale 1.3` → identical text bounds), dialogs ignore the app's own text size, there is still no `NavigationRail` and no width cap (cards are 2228 dp wide), and Persian is still concatenated into LTR sentences (`"not " + term`).

### M3 self-audit (skill's 0/1/2)
task clarity **2** · hierarchy **1** (two "Features" headings) · component semantics **2** · token discipline **1** · adaptive **0** · states & feedback **1** (empty state added; no save/delete snackbar; error styling only in 2 of 3 dialogs) · accessibility **0** (text scaling broken) · expressive restraint **2**.
One `0` in accessibility → **per the skill's own gate this is not fully ready**, which is why the verdict is "ship with fixes" and not "done".

---

## 1. Fix verification (tap path, not code reading)

| Fix | Tap path | Expected | Actual | Verdict | Shot |
|---|---|---|---|---|---|
| **B1** per-action params | Features → `Add rule` → `forward` → `email` | To email / Subject / Body / Email app appear even though `email` is 2nd | All four fields render under a labelled `email` block, plus `Forward number` above | **pass** | `shots/ux2/03-b1-forward-email2.png` |
| **B1** deselect first | …then tap `forward` chip off | `email` block survives | Block intact, only `forward` field removed | **pass** | `shots/ux2/04-b1-email-only-after-deselect-forward.png` |
| **B1** shared param copy | `forward` + `autoreply` + `email` | second non-email action says where its text goes | `Reply text: shares the one parameter field with Forward number.` | **pass** | `shots/ux2/48-small-window-editor-3params.png` |
| **B2** delete confirm | Features → row `Delete` | dialog names the rule; Cancel keeps; Delete removes | `Delete rule?` / `"Cooldown " and its settings will be removed. This cannot be undone.`; Cancel kept it, Delete removed it | **pass** | `shots/ux2/33-b2-delete-confirm.png`, `…/34-b2-after-delete.png` |
| **B3** refused regex dialog | editor → `Message contains…` → `[a-` | OK visibly disabled, reason adjacent, field in error state | OK = grey pill, Cancel = blue text (enabled state = filled blue, compared side by side); reason sits under the field; **field is not `isError`** (grey text, neutral border) | **pass, minus error styling** | `shots/ux2/14-b3-regex-refused.png` vs `…/16-b3-valid-ok-compare.png` |
| **B3** editor Save | plain keyword `[a-` → OK → tap `Regex` | Save blocked and visibly so, reason above it | Save grey filled pill + `Can't save — regex unusable, will never match: [a-` in error pink directly above the button row | **pass** | `shots/ux2/55-b3-editor-save-disabled.png` |
| **B4** OTP single-select | editor → tap `OTP required`, `No OTP`, `Any OTP` | three chips, one selected, clearing lands on Any | exactly one filled at a time; re-tapping the selected chip is a no-op (radio semantics); `Any OTP` is an explicit chip and restores "no constraint" | **pass** | `shots/ux2/05-b4-otp-required.png`, `…/07-b4-otp-no.png`, `…/10-b4-otp-any.png` |
| **B5** reorder | 2 rules → ↓ on row 1 → check ends → force-stop → relaunch | order swaps, arrows disabled at ends, order persists | swapped instantly; up faded on row 1 / down faded on row 2 (opacity measured ~35 % vs 100 %); order survived `am force-stop` + relaunch | **pass** | `shots/ux2/31-b5-reordered.png`, `…/32-b5-persisted.png` |
| **M1** disabled whole-word chip | editor → `Regex` on | chip visibly disabled + note | note reads `Whole words is off under Regex — the pattern sets its own boundaries.`; label dimmed (124,122,127 vs 158,158,158 = 4.66:1 vs 7.4:1); accessibility exposes `enabled=false`; **note is 140 dp below the chip** (chip 401–428 px, note 585–607 px) | **partial** | `shots/ux2/11-m1-regex-on-wholewords.png` |
| **M2** newline splits | editor → `Message contains…` → `adbank` ⏎ `transfer` → OK | two separate terms | chip becomes `Message: adbank, transfer`; reopening shows `adbank, transfer`; helper now says `a, b, c or new lines, leading - excludes` | **pass** (still one text field, no per-term chip/delete) | `shots/ux2/18-m2-newline-two-terms.png` |
| **M3** length | `Length…` → `abc` rejected → 100 / 2000 → OK | numeric fields, sane chip | letters filtered out as typed; chip `Length 100-2000` | **pass** | `shots/ux2/20-m3-length.png` |
| **M3** time | `Time…` | 7 day chips + hour fields, no `||`, invalid hour blocks OK | `Sun…Sat` chips (multi-select: Mon+Tue both filled), `From hour` / `To hour` with `0-23` helpers, empty on open, hour `99` → field + label in error red, `Hours must be 0-23.`, OK disabled; valid 8/22 → OK filled | **pass** | `shots/ux2/22-m3-time-invalid-hour.png`, `…/23-m3-time-days-valid.png` |
| **M4** cooldown clamp | editor → cooldown `999999999999` | cap message | field goes error state, `Save caps this at 86400 s (24 h)`; after save the row reads `max once per 86400s` | **pass** | `shots/ux2/25-m4-cooldown-cap.png` |
| **M5** Test sender+spam | row `Test` → sender `bank`, spam off → spam on | both change the result | `NO MATCH — spam failed` → toggling spam → `MATCH — the actions would run`, live, no re-submit | **pass** (reason still a bare token) | `shots/ux2/36-m5-test-match.png`, `…/37-m5-test-spam-on.png` |
| **M6** empty state | delete all rules | says what/why/next | `No rules yet` + `A rule watches incoming SMS and runs its actions when the conditions match.` + `Add rule` | **pass** | `shots/ux2/35-m6-empty-state.png` |

Short-window check (1080 px tall): editor with `forward`+`autoreply`+`email` scrolls, actions stay pinned, all email fields reachable; no clipping. `shots/ux2/48-…`, `…/49-small-window-editor-scrolled.png`.

---

## 2. M3 conformance scorecard (pass 2)

| Component / area | Expected M3 behaviour | What the app does now | Verdict |
|---|---|---|---|
| Top app bar | one `TopAppBar` per screen | correct on Inbox/Blocked/Test/Appearance/Settings/Features; Ads still hand-rolls a header (title is a single `TextView` 69→2491 px) | partial |
| Page titles | one title per region | `TopAppBar` "Features" + in-body `titleMedium` "Features" (unchanged) | fail (minor) |
| Navigation | drawer on compact, `NavigationRail` when wide | `ModalNavigationDrawer` only, at 2275 dp; sheet is 426 dp (M3 max 360 dp) | fail |
| Wide-window content | bounded readable measure / supporting pane | rule cards 2228 dp wide; reorder arrows 1885 dp from the action group; Blocked/Test fields 2450 px wide with `Add` button at the far right | fail |
| Tabs | `Tab`/`SecondaryTabRow` for peer views | Inbox tab row ok; Ads still two `FilterChip`s as peer views | fail |
| Single-choice controls | `SegmentedButton` | `Any OTP`/`OTP required`/`No OTP` = three `FilterChip`s (behaviour correct, component not the M3 one); `All keywords` toggle remains a chip | partial |
| Action selection | actions are buttons, not chips | 18 raw engine ids as `FilterChip`s (`copy_otp`, `block_sender`, `dismiss`…) | fail |
| Destructive action | explicit + confirm or undo | confirm dialog present, `Delete` in error role (#F2B8B5); distinction is colour-only but the label is explicit | pass |
| Disabled state | perceivable | dialog `OK`/`Save` = grey filled pill vs blue enabled — clear; `Whole words` text 4.66:1 vs 7.4:1 — subtle but present, plus note | pass (weak on the chip) |
| Error state | attached to the control, recoverable | Time/Length/Cooldown use `isError` + supporting text (correct); keyword-regex dialog shows the same message as plain grey body text with a neutral field | partial |
| Dialogs | one modal surface, maximal 560 dp wide, consistent | nested dialogs still stacked (editor → keyword/time); width drifts **512 dp** (keyword) vs **606 dp** (editor, over the 560 dp max) | partial |
| Dialog token | app surface roles | dialog background measured **#2B2930** = baseline M3 `surfaceContainerHigh` (purple-grey) against a black/blue app palette | fail (token) |
| Empty state | present, says what/expected/next | Features now has one; **centred at x 903–1658 px while the page axis is x=16 px** | partial |
| Containment | surface-container roles, not tonal elevation | rule cards still `Surface(tonalElevation = 2.dp)` → measured **#030912 vs #000000 = 1.05:1**, i.e. invisible as a container | fail |
| Snackbar | confirmations with optional action | still none for rule save / enable / delete | partial |
| Touch targets | ≥ 48 dp | re-measured: app-bar 48 dp, chips 48 dp, `Edit/Test/Delete` 54 dp tall, reorder `IconButton` 53×53, dialog buttons 53 dp | pass |
| Text scaling | honours system **and** app setting | system `font_scale 1.3` → title bounds identical (16,101,112,138); app `Extra large` → page title 96×37→126×47 px but **dialog title 113×43 px unchanged** | fail |
| Dark contrast (text) | ≥ 4.5:1 | helper `#9F9F9F` on `#000` ≈ 7.9:1; disabled label 4.66:1; error pink fine | pass |
| Bidi / localisation | isolate interpolations, direction-agnostic layout | `BodyText` still correct for message bodies; rule summary/chips/decisions still concatenate Persian into LTR strings (`"not " + term`, `message contains all of ['…'] AND …`) | fail |
| RTL layout | mirrored layout under RTL | nothing mirrored; Persian preview sits left-aligned in an LTR rail | partial |

---

## 3. Remaining findings, ranked

Sizes: **S** ≤ ½ day · **M** 1–2 days · **L** ≥ 3 days.

| # | Sev | Finding | Evidence | Fix | Size |
|---|---|---|---|---|---|
| R1 | blocker (a11y) | System font scale is discarded: `Density(base.density, fontScale)` (MainActivity.kt:280) replaces it, so a user who set 1.3 sees no change at all. Dialogs additionally ignore the app's own size setting. | `font_scale 1.3` → `Features` still 96×37 px; at `Extra large` page 126×47 but dialog title still 113×43 | multiply: `Density(base.density, fontScale * base.fontScale)`; wrap dialog content in the same `CompositionLocalProvider` (dialogs are separate windows and do not inherit it) | S (2 lines) + S (dialog wrapper) |
| R2 | major (adaptive) | No rail, no measure cap, and the reorder fix made rows full-bleed: cards 2228 dp wide, arrows at x≈2480 px, 1885 dp of dead space between action group and reorder. Drawer sheet 426 dp. | `shots/ux2/30-features-two-rules.png`, `…/29-drawer.png`, `…/52-blocked.png` | §5 structure | M (+L for list-detail) |
| R3 | major (bidi) | Persian is still spliced into LTR strings: `"not " + term` (MainActivity.kt:1239), `conditionSummary` quoting, `decisionLine`, `Test: ${rule.name}`. | source (`grep -n 'not "'`), pass-1 §RTL. No Persian input path on this image (`input text` is ASCII-only, no `cmd clipboard`), so screen-verified only where Latin stands in | §6 isolation | S–M |
| R4 | major (tokens) | Rule cards still 1.05:1: `tonalElevation = 2.dp` over `#000000` = `#030912`. | measured pixel | `Surface(color = surfaceContainerHigh)` or `Card(colors = CardDefaults.cardColors(containerColor = surfaceContainerHigh))` | S |
| R5 | major (hierarchy) | Empty state is centred in the window (x 903–1658) while every other element on the page is on the 16 dp left axis — two axes on one screen, and the state jumps sideways at 2275 dp. | `shots/ux2/35-m6-empty-state.png` | `fillMaxWidth()` + start alignment, keep the column inside the content measure | S |
| R6 | major (hierarchy) | Duplicate page title (`TopAppBar` + `titleMedium "Features"`), still present after the fix. | `shots/ux2/30-features-two-rules.png` | delete the in-body title, keep the helper sentence | S |
| R7 | major (consistency) | Ads page: hand-rolled header (single `TextView` 69–2491 px), `FilterChip`s as peer views, 2452 px-wide search field, duplicated counts (`Ads 0`/`Unsure 0` + `filtered 0 · kept 0 · unsure 0`), training plumbing in overflow (`Export labels for training`, `Import my earlier ad marks`). | `shots/ux2/38-ads.png`, `…/54-ads-overflow.png` | `TopAppBar` + `SecondaryTabRow`; move export/import to Settings → Diagnostics; drop the stats line | M |
| R8 | major (tokens) | Dialog surface #2B2930 = baseline M3 purple-grey; `error`/`primaryContainer`/`tertiary`/`surfaceContainer*` never overridden; FAB is a literal `FAB_AMBER`. | measured dialog pixels | set the 6 missing roles in `miuiDark()/miuiLight()` | S |
| R9 | minor (consistency) | Dialog widths drift: keyword 512 dp vs editor 606 dp (>560 dp M3 max) at the same window. | dump bounds | one `DialogProperties`/`widthIn(max = 560.dp)` for the family | S |
| R10 | minor (copy) | Test result still speaks engine tokens: `NO MATCH — spam failed`, `sender failed`, `keyword failed`. | `shots/ux2/36-m5-test-match.png` | map tokens → sentences (`no sender match`, `the sender is not marked as spam`) | S |
| R11 | minor (copy) | Time chip leaks calendar numbers: `Time 2,3 8-22`. | `shots/ux2/24-m3-after-time.png` | `Time Mon–Tue 08:00–22:00` (day names from the same `DAY_CHIPS` list) | S |
| R12 | minor (feedback) | Saving a rule, toggling Enabled, reordering and deleting all persist silently (delete at least has a confirm). | `shots/ux2/26-rule-saved-cooldown.png` | one `Snackbar` "Rule saved" (+ Undo for delete, which would let the confirm go) | S |
| R13 | minor (states) | Whole-word note sits 140 dp below the chip, under a different row — reads as a page footnote, not a chip state. | `shots/ux2/11-m1-regex-on-wholewords.png` | put the note directly under the condition `FlowRow`'s chip row, or as `supportingText` on the chip row | S |
| R14 | minor (copy/forms) | "Edit rule" title on a brand-new rule; `Any SMS` still claims "any" for what is "run with no conditions"; developer `Self-test` in the primary action row (toast `ALL TESTS PASSED (39)`, detail unreachable); Settings still hardcodes "MIUI Messages" and the Features pointer is still non-tappable text; Blocked / Test a message still use placeholder-as-label fields. | `shots/ux2/03…`, `…/51-selftest-toast.png`, `…/45-settings.png`, `…/52-blocked.png`, `…/53-test-a-message.png` | rename to `New rule`; `Match even without conditions`; move Self-test under Settings; conditional OEM copy; `OutlinedTextField(label=…)` | S each |
| R15 | minor (a11y) | "Mark as spam" switch is an unnamed `checkable` node (`View`, no text/desc) — label sits in a sibling `TextView`. | dump of Test dialog | `Row(Modifier.toggleable(role = Role.Switch)) { Text(...); Switch(...) }` | S |
| R16 | minor (state) | Dialogs: keyword-regex refusal is not an error state (`isError` absent) while Time/Length/Cooldown are — same class of problem, three different treatments. | `shots/ux2/14-b3-regex-refused.png` vs `…/22-m3-time-invalid-hour.png` | add `isError` + `supportingText` to the keyword field | S |

Also worth one line: the `▸/▾` glyph disclosure for Recent decisions is still a character, not `Icon(ExpandMore)`, and entries would render as one LTR line; unverifiable with no SMS data.

---

## 4. What the fixes broke (regressions)

1. **Rule row layout.** `Row(fillMaxWidth, SpaceBetween)` was added to hold the reorder cluster → the row is now full-bleed. Before the fix the row was a ~640 dp column; now `Edit/Test/Delete` end at x=285 px and ↑/↓ start at x=2406 px in a 2560 px window. This is the single most visible regression on a desktop-sized window and it is why R2 matters.
2. **Empty state axis.** The new empty state is `CenterHorizontally` inside a full-width item → centres in the *window*, not in the page column (x 903 vs x 16).
3. **Whole-word note.** Placed after the whole condition `FlowRow`, so it lands below `Any SMS`, 140 dp from the chip it explains.
4. **Dialog height.** With `forward`+`autoreply`+`email` the editor is taller than a 960 dp-tall window and needs a scroll before `Cooldown`/`Enabled` are visible. Actions stay pinned and nothing is clipped (verified), but the content gained two more blocks with no grouping — the editor now reads as one 15-control wall.
5. **Name round-trip.** A trailing space survives into the delete dialog (`"Cooldown "`); cosmetic, but it is the string users see before an irreversible action.

---

## 5. Wide window: proposed M3 adaptive structure (R2)

Current: one compact layout stretched to 2275 dp, drawer-only, no measure.

```
< 600 dp   ModalNavigationDrawer + single pane, 16 dp page inset   (today's layout, keep)
600–839 dp NavigationRail (collapsed, 80 dp) + content widthIn(max = 640.dp), centred
≥ 840 dp   NavigationRail + list-detail:
           left  rules list, 360 dp fixed (title, IF/THEN, Edit/Test/Delete, ↑/↓)
           right selected rule editor as an inline pane (not an AlertDialog), or the
                 "No rule selected" placeholder
```

- Rail items: Inbox, Ads, Blocked, Features, Settings; the three rarely used (Archived, Test a message, Appearance) go to an overflow at the rail's bottom.
- The rule editor is the only stacked modal today; at ≥840 dp it should be the detail pane (M3 prefers a pane/side sheet to a modal on expanded windows), which also removes the 606 dp dialog from the equation.
- `widthIn(max = 640.dp)` alone fixes the 2228 dp cards, the 1885 dp arrow gap, the 2452 px search fields and the `Add` button sitting 2400 px from its input — one modifier per page.
- Drawer sheet: clamp to 360 dp (`ModalDrawerSheet(modifier = Modifier.width(360.dp))`) for the compact case.
- Estimated: rail + measure **~30 lines** in the app shell; list-detail **~150–200 lines** (new composable, reuses `AddonRuleEditor` body).

---

## 6. Bidi: proposal (R3)

Every remaining defect is an *interpolation site*, not the message typography (which is correct via `BodyText`):

1. **Rule chip** (`MainActivity.kt:1239`): `(if (negated) "not " else "") + term` → wrap the term: `"not " + "\u2066$term\u2069"`, or better, render the chip label as an `AnnotatedString` with `BidiFormatter` (`androidx.core.text.BidiFormatter.getInstance().unicodeWrap(term, TextDirectionHeuristics.RTL)`), which also handles the Persian-digit case.
2. **Row summary** (`conditionSummary`): stop building `message contains all of ['…']` from a template. Either (a) isolate each term (`\u2066…\u2069`) and keep the English frame, or (b) render the summary through the same direction detection `BodyText` uses and lay the sentence out with `TextDirection.ContentOrRtl` so a Persian-only rule hugs the same edge as a Persian message.
3. **Recent decisions** (`decisionLine`): one concatenated string is both a bidi problem and a layout problem. Make it a `Row` of four cells (time · sender · body-first-40 · rule+actions) with `TextDirection` per cell; the arrow `→` disappears, which is what pass 1 already asked for.
4. **Test dialog title** `Test: ${rule.name}`: isolate the name.
5. **Notification/Toast strings** (`FilterService`, `IncomingSms`): the only Persian product copy, not localised as a set — keep, but treat as one resource group when strings are extracted.

No new dependency needed: `BidiFormatter` ships with `androidx.core`.

---

## 7. Dead / duplicated UI — pass-1 list, re-checked

| Item | Status now | Evidence |
|---|---|---|
| Two testers under one name (drawer `Test a message` vs rule `Test`) | **unfixed** — still two, no cross-link, still no mention of the user's rules in the drawer one | `shots/ux2/53-test-a-message.png` |
| Ads hand-rolled header duplicates `TopAppBar` | **unfixed** | `shots/ux2/38-ads.png` |
| Ads peer-view chips duplicate the tab pattern | **unfixed** | same |
| Non-tappable "…managed on the Features page." | **unfixed** (no `Button` node around it) | `shots/ux2/45-settings.png` |
| Dev tooling in product surfaces (`Self-test`, Ads export/import) | **unfixed** | `…/51-selftest-toast.png`, `…/54-ads-overflow.png` |
| `keywordContains` legacy field still written | **unfixed** (`MainActivity.kt:1333`) | source |
| Self-test detail discarded (toast shows last line) | **unfixed** — `ALL TESTS PASSED (39)` | `…/51-selftest-toast.png` |
| `AdsPage(onBack = {})` default no-op | **unfixed** (`:385`) | source |
| `Page.Features ->` unindented branch | **fixed** (`:370` normal) | source |
| No empty state for the rules list | **fixed** | `…/35-m6-empty-state.png` |
| `matchAny` semantics undocumented | **unfixed** — chip still `Any SMS` | `…/03-b1-forward-email2.png` |
| Nested modal in the editor | **unchanged** (still `AlertDialog` inside `AlertDialog`) | `…/14-b3-regex-refused.png` |

---

## 8. What to do next, in order, with size

1. **R1 font scale** (S) — the only hard accessibility gate left. Multiply the system scale, pass the same density into dialog content.
2. **R2 width cap + rail** (M) — `widthIn(max = 640.dp)` per page and `NavigationRail` ≥600 dp. Kills the 1885 dp arrow gap and the 2450 px fields in one change.
3. **R3 bidi isolation** (S) — `BidiFormatter.unicodeWrap` at the three interpolation sites; decisions become a `Row`.
4. **R4/R8 tokens** (S) — set `surfaceContainer*`, `error`, `primaryContainer`, `tertiary`; card → `surfaceContainerHigh`; drop `FAB_AMBER`.
5. **R7 Ads page** (M) — `TopAppBar` + `SecondaryTabRow`, one search width, drop stats line, move export/import to Settings.
6. **R10/R11/R12 copy & feedback** (S) — reason sentences, `Time Mon–Tue 08:00–22:00`, "Rule saved" snackbar with Undo on delete.
7. **R5/R6/R13/R16 polish** (S) — empty-state axis, drop duplicate title, note under the chip row, `isError` on the keyword field.
8. **List-detail editor at ≥840 dp** (L) — the only remaining structural M3 gap once the rail exists.
9. **R14/R15 minors** (S each) — `New rule` title, `Match even without conditions`, Self-test to Settings, conditional OEM copy, labelled fields on Blocked/Test, named spam switch.

---

## 9. Coverage caveats (unchanged from pass 1, read before trusting the table)

- **No SMS data.** `content://sms` is empty on this image; Inbox rows, Thread, Ads-with-rows, Blocked-with-rows, Recent-decisions entries and notification previews are still **unverified**. This pass only ever saw their empty states.
- **No Persian input path.** `input text` is ASCII-only (`input text 'سلام'` → NPE) and there is no `cmd clipboard` on this image, so RTL findings are `[source]`-backed at the interpolation sites, plus the Appearance preview which does render Persian correctly.
- **Waydroid died mid-pass** (`waydroid status` → Session STOPPED at 16:46) while saving a rule; restarted with `waydroid session start`, and the saved rule was intact afterwards. No app crash in logcat, no data loss — but a session restart is not a clean signal, so the B5 persistence check was re-run after the restart (order survived a second `force-stop`/relaunch too).
- The white triangle at the bottom-left of every screenshot is a Waydroid window overlay (absent from the a11y tree), not app UI.

## 10. Tap paths used (pass 2, reproducible)

- Features: drawer (32,40) → `Features` (87,701).
- Editor: `Add rule` (88,217); params: `forward` → `email`; deselect `forward`.
- OTP: `Any OTP` / `OTP required` / `No OTP` chips in the IF row.
- Refused regex: `Regex` → `Message contains…` → `[a-` → `OK` (stays). Editor variant: plain `[a-` → OK → `Regex`.
- Length: `Length…` → `abc` (rejected) → 100 / 2000 → OK. Time: `Time…` → `99` (error) → 8 / 22 + Mon+Tue → OK.
- Cooldown: field → `999999999999` → Save.
- Reorder: row ↓ (2497,512) → `am force-stop` → relaunch → drawer → Features.
- Delete: row `Delete` → Cancel → `Delete` → B2 dialog → Cancel / Delete.
- Test: row `Test` → Sender `bank` → spam switch (1586,722).
- Font scale: `settings put system font_scale 1.3` → force-stop → relaunch; `… 1.0` to restore. App size: Appearance → `Extra large` → Normal.
- Short window: `wm size 1920x1080` → editor with 3 param actions → swipe → `wm size reset`.

## 11. Screenshots to show first (12)

1. `shots/ux2/30-features-two-rules.png` — wide window: full-bleed cards, arrows 1885 dp from actions, duplicate title.
2. `shots/ux2/03-b1-forward-email2.png` — B1 fixed: email fields present with `forward` selected first.
3. `shots/ux2/04-b1-email-only-after-deselect-forward.png` — B1 fixed: block survives deselecting the first action.
4. `shots/ux2/14-b3-regex-refused.png` — B3 fixed: grey OK vs blue Cancel, reason under the field (but neutral field).
5. `shots/ux2/55-b3-editor-save-disabled.png` — B3 at editor level: disabled Save + red reason above it.
6. `shots/ux2/05-b4-otp-required.png` — B4 fixed: three chips, one filled.
7. `shots/ux2/11-m1-regex-on-wholewords.png` — M1 half-fixed: dimmed label + note, 140 dp away.
8. `shots/ux2/18-m2-newline-two-terms.png` — M2 fixed: newline → two terms in the chip.
9. `shots/ux2/22-m3-time-invalid-hour.png` — M3 fixed: day chips, hour fields, real error state, blocked OK.
10. `shots/ux2/25-m4-cooldown-cap.png` — M4 fixed: cap surfaced, field in error state.
11. `shots/ux2/33-b2-delete-confirm.png` — B2 fixed: named confirm, error-coloured destructive.
12. `shots/ux2/35-m6-empty-state.png` — M6 fixed, and the centred-axis problem in one image.

Extras: `…/38-ads.png` (R7), `…/43-xl-features.png` (page scaled), `…/48-small-window-editor-3params.png` + `…/49-…` (short window), `…/52-blocked.png` (full-bleed field + placeholder labels), `…/29-drawer.png` (drawer on 2275 dp).
