# TEST-UX — first UX pass on the smsfilter UI (incl. the addon Features editor)

Reviewed: 2026-11-05, Waydroid Android 13, device `192.168.240.112:5555`, installed build `ir.smsfilter` v0.3.11 (14).
Method: driven with `input tap` / `input text` / `keyevent` only; every claim below has a `uiautomator dump` bound or a screenshot behind it. No app code was edited.
Standard: `material-design-3-ui` skill (M3 component semantics, tokens, states, accessibility, adaptive). App is Compose + Material3, so violations are *misuse and inconsistency*, not a port.

Window: 2560×1396 px @ density 180 → **2275×1241 dp**. That is a desktop-class window, which is why several M3 adaptive findings exist; they are marked so they can be judged as "tablet/desktop" rather than "phone".

## Verdict

**Not shippable in the current addon editor state.** Four defects make a saved rule mean something different from what the user asked for — and one of them destroys data:

1. an action's parameter field only appears if that action is the *first* chip selected (email/autoreply/modify_text/alert can be armed with no parameter and no warning);
2. `Delete` removes a persisted rule on one tap, no confirm, no undo;
3. `OK`/`Save` look enabled while a refused regex is silently blocking them, so the click does nothing;
4. the OTP chip cycles Any OTP → OTP required → No OTP, so "turn it off" lands on the *opposite* condition.

The rest of the app (Inbox shell, Ads, Blocked, Settings, Appearance, Test a message) is coherent and M3-plausible; its problems are consistency and empty-state level, not blockers.

---

## Environment caveats (read before trusting the coverage table)

- **No SMS data reachable.** `content://sms` is empty on this Waydroid image and `content insert` into the telephony provider is silently dropped, `run-as` is blocked (`setegid(AID_PACKAGE_INFO) failed`). Inbox, Archived, Thread, Ads-with-rows, Blocked-with-rows and every empty→populated transition are **unverified**, as are notification previews and Recent-decisions entries (they need a real received SMS).
- **No Persian typing.** `adb shell input text` is ASCII-only and there is no clipboard CLI on this image. All RTL findings are judged from source + the one Persian surface that renders without data (Appearance → Preview), and are labelled `[source]` where they were not seen on screen.
- System font scale was deliberately changed to 1.5 and back to test accessibility; restored to 1.0.

---

## Ranked findings (M3 checklist categories, with the rule violated)

| # | Sev | Category | What is wrong (evidence) | Screen / shot | M3 rule violated | Fix (one line) | Code or copy |
|---|-----|----------|--------------------------|---------------|------------------|----------------|--------------|
| B1 | blocker | Forms & input / states | Selecting `email` as a second action shows **no To email / Subject / Body / Email app** fields; deselecting the first action makes them appear. Root cause: parameter UI keyed to `actions.firstOrNull()`. Same for `autoreply` (no Reply text), `modify_text`, `alert`. | Edit rule; `shots/ux-31-email-no-fields.png`, `shots/ux-32-email-fields-when-first.png` | Every input needs a label and the control must exist for the selected option; states & feedback | Render the parameter block for *every* selected action that takes one, stacked under THEN. | code — `app/src/main/java/ir/smsfilter/MainActivity.kt:1118` (`val action = actions.firstOrNull()`), branches at `:1182`, `:1193` |
| B2 | blocker | Destructive action / feedback | `Delete` on a rule row deletes and persists immediately: no dialog, no snackbar, no undo. Screenshot after the tap is a blank page. | Features row; `shots/ux-19-rule-row-final.png` → `shots/ux-22-after-delete.png` | Destructive actions explicit and hard to trigger accidentally; snackbar with one action | Confirm dialog (or undo Snackbar "Rule deleted · Undo"). | code — `MainActivity.kt:999-1003` |
| B3 | blocker | States & feedback | With a refused regex (`[a-`), `OK` in the keyword dialog is `enabled=false` but renders exactly like Cancel; tapping does nothing (verified: dialog stays, no toast, no focus change). Editor `Save` behaves the same. The error line is plain `bodySmall` text 8 dp above the button row. | Keyword dialog; `shots/ux-10-regex-refused.png` | Disabled state must be perceivable; error must be attached to the affected control; never colour-only | Give the button a real disabled style *and* the field an error state + error-supporting text (`isError`, `supportingText`). | code — `MainActivity.kt:1275`, `:1210`, error text `:1269`, `:1201` |
| B4 | blocker | Component semantics | `Any OTP` is a **tri-state** chip: tap 1 → `OTP required`, tap 2 → `No OTP`, tap 3 → `Any OTP`. Clearing the condition forces the user through the opposite condition. | Edit rule IF row; `shots/ux-33-otp-tri-state.png` | One mutually exclusive selection → SegmentedButton (or radio), not a cycle chip | `SegmentedButton` with three options, or a two-state chip + a separate "No OTP" chip. | code — `MainActivity.kt:1157` (`otpMode = (otpMode + 1) % 3`) |
| B5 | blocker (coverage) | Hierarchy / IA | Page says "Every matching rule runs, **in priority order**", the engine sorts by `priority`, and there is no UI anywhere to set or change it: new rules get `priority = rules.size`, no drag handle, no index, no reorder action. | Features; `shots/ux-02-features.png`, `shots/ux-19-rule-row-final.png` | Do not promise order the user cannot control | Drag handle + `priority` rewrite on drop (or explicit ↑/↓). | code — `MainActivity.kt:955` (`priority = rules.size`), row `:984-1005` |
| M1 | major | States & feedback | `Whole words` is `enabled = !keywordRegex`, but at Regex-on it looks pixel-identical to its neighbours — verified on the shot, tap does nothing. | `shots/ux-39-regex-wholewords.png` | Disabled state must be perceivable, not inferred | M3 disabled content alpha (0.38) or hide the chip while Regex is on with a helper line. | code — `MainActivity.kt:1148` |
| M2 | major | Forms & input | Keyword entry is one dialog with an invisible model: no term list in the editor, no per-term delete, one chip whose label grows with every term. Separator rules are contradictory: non-regex label is `a, b, c, leading - excludes`, regex label is `one regex per line, leading - excludes`. A newline typed in non-regex mode is **kept inside the term** — verified `adbank\ntransfer` became one keyword that can never match, with no error. | `shots/ux-04-keyword-dialog.png`, `shots/ux-05-keyword-typed.png`, `shots/ux-09-editor-newline-term.png` | Input model must be visible; validation must be recoverable | Split on `[\n,]` in both modes and show terms as input chips (one removable chip per term). | code — `MainActivity.kt:1140-1141`, `:1247`, `:1279` |
| M3 | major | Copy / semantics | Negation has no affordance: you must know to type `-`; the chip then renders `not <term>` by string concat (`"not " + term`). In Persian this is an ASCII prefix spliced in front of RTL text with no isolation. | `shots/ux-05-keyword-typed.png` | Localisation must not assume LTR; state must not depend on a hidden syntax | Input chip with a leading "not" toggle, and isolate (`\u2066…\u2069`) when interpolating into a label. | code — `MainActivity.kt:1141` |
| M4 | major | Forms & input | Length and Time dialogs ask for raw syntax: label `min,max`, label `days|start|end`, and Time opens prefilled with the literal string `||`. No legend for day tokens, no hint that hours are 0-23, no validation. | `shots/ux-34-length-dialog.png`, `shots/ux-36-time-dialog.png` | Use pickers/structured controls when they reduce error; placeholders are not labels | Two number fields with min/max hints; day chips (Sat…Fri) + two time pickers. | code — `MainActivity.kt:1259-1267`, dialog seeding `:1160`, `:1163` |
| M5 | major | Validation | Cooldown accepts 12 digits and silently clamps on save (`999999999999` → `max once per 86400s`); the field keeps showing the unclamped value until the dialog closes. No error, no hint of the cap beyond the placeholder. | `shots/ux-14-cooldown-999999.png` | Validate at a moment that helps recovery; error must identify problem + recovery | `isError` + supporting text "max 86400 (24 h)", or clamp on blur with visible value update. | code — `MainActivity.kt:1198-1199`, `AddonRules.clampCooldown` |
| M6 | major | Copy / semantics | `Any SMS` sits in the condition row and reads as "this rule matches every SMS", but it only bypasses the "no conditions → never fires" guard; with a keyword set it changes nothing. | `shots/ux-03-editor-blank.png` | Labels must describe the behaviour the code has | Rename to `Match even with no conditions` (helper text) or remove and let an empty rule mean "any SMS" with an inline warning. | copy — `AddonRules.kt:197`, label `MainActivity.kt:1166` |
| M7 | major | Feedback | Rule `Test` takes a message body only — no sender field — so a rule with `sender contains`, `from contacts`, `spam only` can never MATCH in the dialog; the failure reason is a bare engine token and the template makes two of them ungrammatical: `NO MATCH — disabled failed`, `NO MATCH — no conditions failed`, `NO MATCH — keyword failed`. | `shots/ux-16-test-empty.png`, `shots/ux-17-test-nomatch.png`, `shots/ux-20-test-match.png` | Error messages must be perceivable and explain recovery | Add sender + from-contact + spam toggles to the dialog; map reason tokens to sentences ("no keyword matched", "rule is disabled"). | code — `MainActivity.kt:1026`, `:1037`; `AddonRules.missReason:203` |
| M8 | major | Empty states | Features with zero rules renders **nothing** under the buttons: no "No rules yet", no explanation of the model, no pointer to Add rule. Every other list in the app has an empty state. | `shots/ux-02-features.png`, `shots/ux-22-after-delete.png` | Empty state must state what it means, that it is expected, and the next action | "No rules yet — add one to act on matching SMS" + button. | code — `MainActivity.kt:963-1007` |
| M9 | major | Typography / hierarchy | Two titles stack: TopAppBar `Features` and a second `titleMedium` "Features" immediately below, then helper text. Duplicate heading weakens the screen's only hierarchy cue. | `shots/ux-02-features.png` | One title per screen region; typography must clarify hierarchy | Drop the in-body title, keep TopAppBar; promote helper text to `bodyMedium`. | code — `MainActivity.kt:950` |
| M10 | major | Adaptive layout | At 2275×1241 dp the content is a 640 dp column pinned to the left (helper text ends at x=719 px) — ~72 % of the window is empty, no max-width wrapper, no supporting pane, and navigation is still `ModalNavigationDrawer`. | `shots/ux-02-features.png`, `shots/ux-26-ads.png` | Wide windows need NavigationRail and bounded readable widths, not a stretched compact canvas | `NavigationRail` above ~600 dp, content `widthIn(max = 640.dp)` centred. | code — `MainActivity.kt:275` |
| M11 | major | Accessibility | Text scaling is bypassed twice. (a) `Density(base.density, fontScale)` replaces the system font scale: system `font_scale=1.5` measured **zero** change in any text bound. (b) `AlertDialog` content ignores the app's own setting: page heading 31→40 px at Extra large, dialog title stayed 27 px, chip labels unchanged. | `shots/ux-40-fontscale-1.5.png`, `shots/ux-44-dialog-xl.png` | Support text scaling; test longer/larger strings | Multiply the system scale instead of replacing it; pass the scaled density into dialogs explicitly. | code — `MainActivity.kt:274` |
| M12 | major | Consistency / components | Ads draws its own header instead of `TopAppBar` (title at x=59 vs x=64 elsewhere), has an overflow `⋮` that no other page has, and uses `FilterChip` for peer views (`Ads`/`Unsure`) where Inbox uses `ScrollableTabRow` for the same job. | `shots/ux-26-ads.png` vs `shots/ux-00-start.png` | Same role → same component; do not use chips for navigation/tabs | Use `TopAppBar` + `SecondaryTabRow` for both. | code — `MainActivity.kt:309`, `:379`, `:490-499` |
| m1 | minor | Copy | New rule opens with title `Edit rule` and a name field already containing `New rule` as a *value*, not a placeholder. | `shots/ux-03-editor-blank.png` | — | `if (isNew) "New rule"` title; empty field with `Hint`/label. | code — `MainActivity.kt:1131`, `:955` |
| m2 | minor | Copry / hierarchy | `Self-test` (a developer diagnostic) sits beside the primary action and reports a Toast `ALL TESTS PASSED (39)`; per-check detail exists only in logcat. | `shots/ux-23-selftest-toast.png` | Do not put dev tooling in the primary action group | Move under Settings → Diagnostics; show failures inline. | code — `MainActivity.kt:958-961` |
| m3 | minor | Colour / tokens | Rule rows use `Surface(tonalElevation = 2.dp)` over `surface = #000000`: measured container `#030912` vs `#000000` = **1.05:1**, so the rows read as loose text, not units. | `shots/ux-19-rule-row-final.png` | Prefer surface-container roles over tonal elevation for routine hierarchy; verify in dark theme | `Card`/`Surface(color = surfaceContainerHigh)` or a `HorizontalDivider`. | code — `MainActivity.kt:985` |
| m4 | minor | Copy | Action chips expose engine ids (`copy_otp`, `block_sender`, `modify_text`, `mark_read`) while condition chips are sentences; a user cannot tell `dismiss` from `mark_read` without trying them. | `shots/ux-03-editor-blank.png` | Component labels must be user language | Display names ("Copy OTP", "Block sender"), keep ids in code only. | copy — `MainActivity.kt:1171-1172` |
| m5 | minor | Feedback | Recent decisions is a `TextButton` with a `▸`/`▾` glyph as disclosure, then plain LTR `Text` lines; no timestamp/rule columns, no monospace, no count. | `shots/ux-21-recent-decisions.png` | Use the platform expandable-list affordance, not a character as icon | `ListItem` with trailing `Icon(ExpandMore)` + `AnimatedVisibility`. | code — `MainActivity.kt:967-975` |
| m6 | minor | Copy | Helper text never explains the model it invokes: no mention that `-term` excludes, that `All keywords` off means *any*, that several actions may be selected, or that an empty rule never fires. "BuzzKill-style" names a competitor the user may not know. | `shots/ux-02-features.png` | Helper text must reduce uncertainty | Two-line model summary + inline hint under IF. | copy — `MainActivity.kt:951` |
| m7 | minor | Information density | Ads shows the same numbers twice: chips `Ads 0` / `Unsure 0` and the line `filtered 0 · kept 0 · unsure 0`. | `shots/ux-26-ads.png` | Do not show information merely because space exists | Keep the chips, drop the stats line (or make it time-scoped). | code — `MainActivity.kt:~505` |
| m8 | minor | Placement | Ads overflow carries training/dev actions: `Export labels for training`, `Import my earlier ad marks`. | `shots/ux-26-ads.png` (menu opened below) — see tap path | Product surfaces should not expose training data plumbing | Move to Settings → Diagnostics. | code — `MainActivity.kt:455-480` |
| m9 | minor | Copy | Settings states `MIUI Messages still receives SMS. Ads are only hidden from notifications.` — a hardcoded claim about a specific OEM app, wrong on any other device, and it contradicts Ads' own copy. | `shots/ux-25-settings.png` | Copy must describe the running behaviour | Make it conditional on default-SMS status. | copy — `MainActivity.kt:900` |
| m10 | minor | Consistency | Settings says `SMS features (BuzzKill-style) are managed on the Features page.` as plain, non-tappable text — a pointer that cannot be followed. | `shots/ux-25-settings.png` | Navigation must not be styled as body text | Make it a `ListItem` that navigates. | code — `MainActivity.kt:930` |
| m11 | minor | Forms | Blocked page uses placeholder-as-label fields (`number or shortcode`, `any message containing…`) while Features/Settings use `OutlinedTextField` with floating labels. | `shots/ux-27-blocked.png` | Placeholder is not the only label | Same `OutlinedTextField` + label. | code — `MainActivity.kt:696-760` |
| m12 | minor | Spacing | Side insets drift: 12 dp (Features), 14 dp (Appearance), 16 dp (Blocked/Ads chips), 55 dp (Ads search field). | all shots | Coherent spacing scale, stable axes | One page padding token (`16.dp`) everywhere. | code — `:949`, `:816`, `:699`, `AdsPage` |
| m13 | minor | Feedback | Saving a rule and toggling `Enabled` write to prefs silently — no snackbar; only the row changes. | `shots/ux-15-rule-row.png` | Success feedback proportional to the action | Snackbar "Rule saved". | code — `MainActivity.kt:1012-1017`, `:989-992` |
| m14 | minor | Motion / stability | The keyword dialog changes height with content and jumps vertically between states (open tall → shrinks to 3 lines). | `ux-04` → `ux-05` | Stable layout; motion only to explain change | Fixed min height for the dialog body. | code — `MainActivity.kt:1249` |

### Two behaviours worth naming separately

- **Two dry-runs, two names, no link.** `Test a message` (drawer) runs the *core classifier* and prints `SPAM / score / why / normalized` (`shots/ux-30-testpage-result.png`); rule `Test` runs *addon rules* and prints MATCH/NO MATCH. Neither mentions the other, both are called "test", and the drawer one says "see what the filter would do with it" although it never evaluates the user's rules. → consolidate into one tester with two result sections.
- **`Any SMS` + zero conditions.** A rule with no condition and `Any SMS` off is silently inert; the row summary says `no conditions — never fires`, which is honest, but nothing in the editor says it before saving.

---

## M3 conformance scorecard

| Component / area | Expected M3 behaviour | What the app does | Verdict |
|---|---|---|---|
| Top app bar | One `TopAppBar` per screen: nav icon + title + actions | `TopAppBar` on Inbox/Archived/Blocked/Test/Appearance/Settings/Features; Ads hand-rolls its own header | partial |
| Navigation | Drawer on compact; `NavigationRail` when width allows | `ModalNavigationDrawer` only, at 2275 dp wide | fail |
| Tabs | `Tab`/`SecondaryTabRow` for peer views | Inbox `ScrollableTabRow` (ok); Ads uses two `FilterChip`s for the same job | fail |
| Filter chips (criteria) | `FilterChip` for filtering a collection, multi-select allowed | Used correctly for regex/whole-words/from-contacts/spam/any-SMS | pass |
| Single-choice mode chips | `SegmentedButton` | `All keywords` (AND/OR) and `Any OTP` (3-state cycle) are `FilterChip`s | fail |
| Action chips | chips are not actions; actions are buttons | 18 engine-id `FilterChip`s act as the rule's action set | fail |
| Disabled state | visibly disabled (alpha/tonal) | `Whole words` and blocked `OK`/`Save` look enabled | fail |
| Text fields | persistent label + helper/error text | Editor uses floating labels (good); Length/Time/Cooldown/Blocked rely on placeholder text; errors are unattached body text | partial |
| Dialog | blocking decision, one modal surface, actions pinned | Editor is a scrollable dialog (actions pinned, ok) but stacks a second modal (keyword/length/time) on top | partial |
| Destructive action | explicit, confirm or undo | `Delete` is a plain `TextButton`, immediate | fail |
| Empty state | says what/expected/next | Inbox, Ads, Archived, Blocked yes; **Features no** | partial |
| Snackbar | brief confirmation with optional action | Used on Ads export; not used for rule save/delete | partial |
| FAB | primary, `primaryContainer`; one per screen | Inbox FAB hardcodes `FAB_AMBER = #FFB300` (raw colour, no tertiary role); no FAB elsewhere | partial |
| Cards / containment | surface-container roles for grouping | Rule rows: `tonalElevation=2.dp` on `#000` → measured 1.05:1 | fail |
| Colour roles | semantic roles, no scattered literals | Only 7 roles overridden (`primary`, `secondaryContainer`, surfaces, `outline`); `error`, `primaryContainer`, `tertiary`, `surfaceContainer*` fall back to baseline M3 purple/pink; FAB amber is a literal | partial |
| Typography | one title per region, coherent scale | Duplicate `Features` title; dialog copy sizes do not follow the app's own scale setting | partial |
| Shape | one radius logic per role | Dialog 24 dp, chips ~8 dp, input 4 dp, rule card 12 dp, thread bubbles 9/10/12/16 dp — five values, no scale | partial |
| Spacing | 4 dp rhythm, stable axes | `Arrangement.spacedBy(8.dp)`/`(4.dp)` in editor, 10 dp in Appearance, 12/14/16 dp page insets | partial |
| Text scaling | honours system + app setting | System scale ignored; dialogs ignore the app setting | fail |
| Touch targets | ≥ 48 dp | verified: app-bar icons 48 dp, all chips 48 dp, Edit/Test/Delete 48 dp tall, Save/Cancel 48 dp – **pass** | pass |
| Dark-theme contrast | ≥ 4.5:1 body, ≥ 3:1 graphics | helper text `#9F9F9F` on `#000` ≈ 7.9:1 (measured); chip borders `#3A3A3A` on `#000` ≈ 1.85:1 (graphics, borderline) | pass (text) / partial (borders) |
| RTL / localisation | direction-agnostic layout, isolated interpolations | Persian *bodies* go through `BodyText` (`TextDirection.Rtl`, `TextAlign.Right`) — correct; Persian inside rule summaries, chips, decisions, toasts is raw concatenation | fail |

Self-audit score (skill's 0/1/2): task clarity 1 · hierarchy 1 · component semantics 0 · token discipline 1 · adaptive 0 · states & feedback 0 · accessibility 0 · expressive restraint 2. **Two `0`s in semantics/states and one in accessibility → not ready for approval**, per the skill's own gate.

---

## Coverage table — what TEST-S1…S8 verified vs what a user can reach

| Behaviour (test team evidence) | Reachable in UI? | Exact tap path | If no/partial — what is missing |
|---|---|---|---|
| Normalisation: Persian/Arabic variants, Persian digits, ZWNJ, letter-spacing, squashed spaces (S1) | partially | Inbox → any message (needs data); Test a message → Check shows `normalized:` for the classifier only | no surface shows the *addon* normalisation of a keyword; a user cannot see why `لغو` did not match `ل غ و` |
| Keyword list (S2) | yes | Drawer → Features → Add rule → `Message contains…` → `a, b, c` → OK | terms collapse into one chip label; no per-term delete |
| ALL / ANY over keywords (S2) | partially | same editor → `All keywords` chip toggles label to `Any keyword` | off-state does not say "any"; no helper text; chip is a single-choice control rendered as a filter chip |
| Regex toggle (S2) | yes | editor → `Regex` | none |
| Refused/too-complex pattern message (S2d) | partially | editor with `Regex` on → open keyword dialog → type `[a-` | message appears only inside the keyword dialog and again above Enabled; `OK`/`Save` do not look blocked (B3) |
| Regex caps (200-char subject, ≤ 2 quantifiers) (S2b/c) | no | — | only surfaces as "too complex or invalid"; caps are undocumented |
| Whole-word Unicode boundaries (S8) | partially | editor → `Whole words` | chip is invisible-disabled while Regex is on (M1) |
| `-term` negation (S7) | partially | keyword dialog → type `-term` | no toggle/affordance; chip shows `not term`; no way to negate a term that itself starts with `-` (S7 G1) |
| Every matching rule runs, priority order (S3) | partially | drawer → Features (row list order) | order is insertion order only; no reorder UI (B5) |
| Multi-action, all run (S3) | partially | editor THEN row, multi-select | parameters for non-first actions are unreachable (B1) |
| Cooldown (S3) + clamp (S5) | partially | editor → Cooldown field | clamp is silent; value not shown after save except in the row summary |
| Inert empty rule / Any SMS (S3) | partially | editor → `Any SMS` | label implies "matches everything"; semantics unstated (M6) |
| Per-rule dry-run (S5) | partially | row → `Test` | body only; no sender/spam/contacts input; reason tokens ungrammatical (M7) |
| Decision trace + truncation + corruption self-heal (S5/S6) | partially | Features → `Recent decisions` | entries require a real SMS; no entry format explanation; line is plain LTR text |
| Per-rule enable/disable (S3) | yes | row → Switch | none |
| Email action fields (S3) | partially | editor → `email` chip | fields only exist if `email` is the first action (B1) |
| `dismiss` / `modify_text` / `alert` semantics (S4) | partially | editor → those chips | `modify_text`/`alert` text parameter only when first (B1); `dismiss` has no explanation of when it fires |
| Sender substring match | yes | editor → `Sender contains…` | no scope choice (sender-only substring), no hint that it is normalised |
| Length min/max | partially | `Length…` → `min,max` | raw CSV, no validation, no error on junk (`abc` saved as empty) |
| Time window (days + hours) | partially | `Time…` → `days|start|end` | raw pipe syntax, prefilled `||`, day tokens undocumented, no picker |
| OTP required / no OTP | partially | `Any OTP` chip | tri-state cycle (B4) |
| Self-test matrix (dev) | yes | Features → `Self-test` | result is a Toast; detail only in logcat |

---

## Dead, duplicated and forgotten UI

| Item | Where | Evidence | Verdict |
|---|---|---|---|
| Two testers doing different jobs under one name | `TestPage` `MainActivity.kt:786` vs rule Test `:1023` | `shots/ux-30-testpage-result.png` vs `shots/ux-20-test-match.png` | merge; at minimum cross-link and rename the drawer one ("Why was this filtered?") |
| Ads hand-rolled header duplicates `TopAppBar` | `:309` suppresses TopAppBar for Ads, `:379` builds its own | `ux-26-ads.png` | dead branch + drift; delete the special case |
| Peer-view chips duplicate the tab pattern | `:490-499` vs `ScrollableTabRow` `Messenger.kt:303` | `ux-26-ads.png`, `ux-00-start.png` | one component for one concept |
| Non-tappable section pointer | `:930` "…managed on the Features page." | `ux-25-settings.png` | text pretending to be navigation |
| Dev tooling in product surfaces | `:958` Self-test; Ads overflow `:455-480` (export/import training labels) | `ux-23-selftest-toast.png`, `ux-26-ads.png` | move to a Diagnostics section |
| `keywordContains` legacy field still written | editor `:1214`; read by `AddonRules.conditions` as fallback | not visible | invisible duplicate of `keywords[0]`; no UI can edit it — either migrate on load or drop |
| Self-test detail discarded | `FeatureSelfTest.run` returns every line `FeatureSelfTest.kt:1-77`; UI shows `lines().last()` `MainActivity.kt:960` | `ux-23-selftest-toast.png` | failure detail is unreachable |
| `AdsPage(onBack = {})` default no-op | `:379` | wired correctly at `:348` | latent dead-end if reused; remove the default |
| `Page.Features ->` unindented branch | `:364` | source only | hygiene, no user effect |
| No empty state for the rules list | `:963-1007` | `ux-02`, `ux-22` | forgotten state |
| `matchAny` reachable but semantics undocumented | `:1166`, `AddonRules.kt:197` | `ux-03` | label contradicts behaviour |

No composable is unreferenced — a static scan of every `fun UpperCamel(` in `app/src/main/java/ir/smsfilter/**` found zero definitions without a call site, so the dead UI is duplicated *paths*, not orphan composables.

---

## Consistency: everything the eye compares

- **Typography.** Page title exists twice on Features (`TopAppBar` `titleLarge` + `titleMedium` "Features"); Ads title is drawn manually at a different inset (x=59 vs x=64 px) and appears smaller.
- **Shape scale.** Five radii with no rule: dialog 24 dp (M3 default), chips ~8 dp, text fields 4 dp, rule card 12 dp, thread bubbles 9/10/12/16 dp (`Messenger.kt:579,681,1006,1036,1071,1291`).
- **Chips.** Features chips are outlined rounded-rects; Ads chips are pill-shaped; Inbox tabs are underline tabs; Appearance uses the same chip component for mutually exclusive choices. Four visual grammars for "pick one".
- **Dialogs.** Editor and keyword/Time/Length dialogs all use `AlertDialog` — including a nested one on top of the editor, which M3 asks you to avoid.
- **Colour.** `surface`/`background` are `#000000`; rule-card tonal elevation measures 1.05:1. `FAB_AMBER` is a hardcoded literal (`Messenger.kt:113`). Roles that were never set (`error`, `primaryContainer`, `tertiary`, `surfaceContainer*`) silently fall back to baseline M3 purple; the error text therefore renders in default M3 error pink, a colour that appears nowhere else in the palette.
- **Spacing.** Page insets 12/14/16 dp, editor stack `spacedBy(8.dp)`, inner group `4.dp`, Appearance `10.dp` — three rhythms in four screens.
- **Empty states.** Inbox: `No messages` + explanation. Ads: `Nothing filtered yet` + explanation. Archived/Blocked: same shape. Features: nothing. The best-written states are the ones with data plumbed; the addon page never got one.

---

## RTL / bidi

What is right: every message body is rendered through `BodyText` (`BodyText.kt:26-52`), which sets `TextDirection.Rtl` + `TextAlign.Right` for Persian and pre-computes `InboxPolicy.forDisplay` off the scroll path. Appearance → Preview shows correct Persian shaping (`shots/ux-24-appearance.png`).

What is wrong (`[source]` = judged from the string construction; this image has no Persian input path):

1. **`not ` prefix on a Persian term** — `MainActivity.kt:1141` builds `"not " + term`. In RTL the neutral prefix and the following Persian word reorder, so the chip reads `term not` (or splits the term's first letter). Fix: `\u2066not\u2069` or a leading "not" icon; or an input chip with a struck-through term.
2. **Rule summary** — `conditionSummary` (`MainActivity.kt:1045-1069`) produces `message contains all of ['کد'] AND message does not contain ['لغو']`: Persian inside ASCII quotes inside an English sentence, with `AND` between. The closing quote and `AND` drift to the wrong side, and the row's `Text` is LTR-aligned, so a Persian-only summary hugs the left edge while the app's own message rows hug the right. Use `BodyText` for the summary or isolate each term.
3. **Recent decisions** — `AddonRules.decisionLine` (`:553-572`) concatenates `HH:mm · sender · <first 40 code points of a Persian body> → rule: actions` and the page renders it with a plain `Text` (`MainActivity.kt:975`). Same defect, plus the `→` arrow sits between an RTL run and an LTR rule name.
4. **Test dialog result** — `NO MATCH — ${reason} failed` (`:1037`) is fine, but `Test: ${rule.name}` where the name is Persian produces a mixed title with no isolation.
5. **Toast / notification strings** — `FilterService.kt:120-121` and `IncomingSms.kt:251` are Persian in an otherwise English app: the notification title mixes `$count تبلیغ فیلتر شد` with digits; correct today because Android's notification text runs bidi, but it is the only Persian product copy in the app and it is not localised as a set.

Justified departures: dense rows, true-black theme, and Persian-first message typography are product decisions (MIUI-like SMS app), and they work — the failures above are *not* those departures, they are missing isolation at interpolation points.

---

## What already looks right

- Touch targets: every interactive element measured ≥ 48 dp (app-bar icon 48, chips 48, row buttons 48 tall, dialog buttons 48).
- Primary action is obvious: `Add rule` is the only filled button on Features, `Self-test` is a text button beside it.
- The editor is a single scrollable surface with the action row pinned — it survives the IME (`shots/ux-14-cooldown-999999.png`, dialog re-lays out to 27…1100 px and all content stays reachable).
- Refused regex is *blocked*, not just warned: `OK`/`Save` really do refuse (verified by tapping) — the defect is only that the block is invisible.
- The keyword dialog tells you the separator in its own label, and switches to `one regex per line` in regex mode.
- Row summary is honest: `IF message contains all of ['bank'] AND message does not contain ['transfer']` / `THEN copy_otp + autoreply · max once per 86400s` — it names all/any, negation and cooldown, and `no conditions — never fires` for an inert rule.
- Empty states elsewhere are well written (`Nothing filtered yet` + what happens next; `No archived conversations` + the gesture).
- Deleting the last rule returns to a clean page instantly — no stale state, no crash.
- `Recent decisions` is collapsed by default, so rules stay the page's subject.

---

## BuzzKill parity judgement

Read `BUZZKILL-GAP.md`; BuzzKill composes a rule as a sentence (`When <app> <condition>… <action>`) with a keyword **tree** (`Combination{operation, not, items}`), per-term scope/match-type, and stateful plugins. What we have today is a flat list of conditions + a chip wall of 18 raw action ids.

Judgement: **we should move toward the sentence for the *summary*, not for the whole editor — and only as far as the fields we actually have.** Concretely, and with no new engine features:

1. The row summary is already 80 % of a sentence (`IF … THEN …`). Render it as one readable sentence with the app's own `BodyText`/bidi handling instead of a debug string with brackets and quotes. This is the cheapest, highest-value parity step and needs no schema change.
2. Inside the editor, keep chips but **group them into labelled rows** that mirror the sentence: `When` → sender / keywords / extras; `Only if` → contacts / spam / length / time / OTP; `Then` → actions. The current single flow row makes `Any SMS` look like a keyword.
3. Do **not** copy BuzzKill's app-scope, notification triggers, geofence, DND, sensors or plugin stack — we do not have them and the gap matrix parks them correctly. Do not invent a nested keyword tree either: `ALL/ANY` + `-term` covers the 95 % case and S7 already hardens the negation path.
4. One thing worth stealing from BuzzKill that we *do* have data for: per-condition failure naming in the dry-run (`TraceCode` equivalent). `missReason` already returns one token per failing condition — turn it into a sentence per condition and the Test dialog becomes a real "why didn't it fire" surface (fix M7 and this arrives for free).
5. Reorder/priority (B5) is the parity gap that hurts most in daily use; BuzzKill keeps rule order stable and user-visible. A drag handle is enough.

Verdict on the "chip wall" question: replace the *wall* with a grouped, sentence-shaped flow; do not replace the chips with free-text rule authoring — the app has no parser and does not need one.

---

## Tap paths used (reproducible)

All from a cold app start; device `192.168.240.112:5555`, screen 2560×1396.

- **Drawer**: tap (32,36) → `shots/ux-01-drawer.png`.
- **Features**: drawer → `Features` (75,582) → `shots/ux-02-features.png`.
- **Add rule / editor**: Features → `Add rule` (76,184) → `shots/ux-03-editor-blank.png`.
- **Keyword dialog**: editor → `Message contains…` (1214,455) → `shots/ux-04-keyword-dialog.png`; typed `bank,transfer,-ad` → `shots/ux-05-keyword-typed.png`; newline test → `shots/ux-08-keyword-newline.png`.
- **Refused regex**: editor → `Regex` chip → `Message contains…` → type `[a-` → `shots/ux-10-regex-refused.png`; tap `OK` → dialog stays (`ux-11-regex-ok-tapped.png`).
- **Chip label growth**: `shots/ux-12-chip-long-list.png`; **All/Any** toggle → `shots/ux-38-any-keyword.png`.
- **Action parameter trap**: editor → `email` (second action) → `shots/ux-31-email-no-fields.png`; tap `copy_otp` off → `shots/ux-32-email-fields-when-first.png`.
- **OTP tri-state**: editor → tap `Any OTP` three times → `shots/ux-33-otp-tri-state.png`.
- **Length dialog**: `Length…` → `shots/ux-34-length-dialog.png`, junk input `abc` → `shots/ux-34-length-invalid.png`, `100,2000` → chip `Length 100-2000`.
- **Time dialog**: `Time…` → prefilled `||` → `shots/ux-36-time-dialog.png`.
- **Cooldown clamp**: editor → cooldown field → `999999999999` → `shots/ux-14-cooldown-999999.png`; Save → row shows `max once per 86400s` (`ux-19-rule-row-final.png`).
- **Save / row**: Features → row → `shots/ux-19-rule-row-final.png`.
- **Rule Test**: row → `Test` (130,435) → body `bank 123456` → `shots/ux-20-test-match.png`; no-match body → `shots/ux-17-test-nomatch.png`.
- **Recent decisions**: Features → `Recent decisions` (100,246) → `shots/ux-21-recent-decisions.png`.
- **Delete**: row → `Delete` (217,455) → `shots/ux-22-after-delete.png` (no confirm, no undo).
- **Self-test**: Features → `Self-test` (190,184) → `shots/ux-23-selftest-toast.png`.
- **Appearance**: drawer → `Appearance` → `shots/ux-24-appearance.png`; `Extra large` → `shots/ux-41-appearance-xl.png`; system `font_scale 1.5` → `shots/ux-40-fontscale-1.5.png`.
- **Settings**: drawer → `Settings` → `shots/ux-25-settings.png`.
- **Ads**: drawer → `Ads` → `shots/ux-26-ads.png`.
- **Blocked**: drawer → `Blocked` → `shots/ux-27-blocked.png`.
- **Archived**: drawer → `Archived` → `shots/ux-28-archived.png`.
- **Test a message**: drawer → `Test a message` → `Check` (67,322) → `shots/ux-30-testpage-result.png`.
- **Inbox**: cold start → `shots/ux-00-start.png`.

## Screenshots to show first

1. `shots/ux-02-features.png` — page with no rules: duplicate title, no empty state, 72 % empty window.
2. `shots/ux-03-editor-blank.png` — the chip wall (18 action ids, tri-state OTP, first-action default).
3. `shots/ux-31-email-no-fields.png` — email selected, no email fields (blocker B1).
4. `shots/ux-32-email-fields-when-first.png` — same rule, fields appear only when email is first.
5. `shots/ux-10-regex-refused.png` — refused regex with an `OK` that looks enabled but does nothing (B3).
6. `shots/ux-22-after-delete.png` — blank page after one-tap delete (B2).
7. `shots/ux-19-rule-row-final.png` — row summary, the one place the model reads correctly.
8. `shots/ux-36-time-dialog.png` — `days|start|end`, prefilled `||`.
9. `shots/ux-26-ads.png` — Ads: own header, chips as tabs, dev items in overflow, redundant counts.
10. `shots/ux-44-dialog-xl.png` — Extra large text: page scaled, dialog did not (M11).
