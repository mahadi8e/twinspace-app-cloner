# TwinSpace v1 — Changes vs NewBlackbox upstream `89b59836c6`

**Status: compile-unverified — no Gradle build has been run (Gradle cannot run in this VM). First build happens via GitHub Actions after Dec 1, then a real-phone test. Everything below is code-complete and statically checked only (see §5).**

Working copy of ALEX5402/NewBlackbox (Apache-2.0). The pristine upstream clone in `../upstream/` is untouched. Commits on top of `a4d6141` ("base: NewBlackbox upstream 89b59836c6"):

| Commit | Group |
|---|---|
| `2d3fcdf` | Rebrand |
| `8a54858` | Workflow security |
| `930afe5` | Split-APK fix |
| `3a9805e` | Black & white dark theme |
| `38ac656` | Space-card home UI |

---

## 1. Rebrand (`2d3fcdf`)

| File | Before → After |
|---|---|
| `app/build.gradle` | `applicationId "top.niunaijun.blackbox"` → `"com.twinspace.cloner"`; APK output `BlackBox_<ver>_<abi>.apk` → `TwinSpace_<ver>_<abi>.apk` |
| `settings.gradle` | `rootProject.name = "NewBlackbox"` → `"TwinSpace"` |
| `app/src/main/res/values/strings.xml` (+ `values-zh-rCN`, `values-zh-rTW`) | `app_name` `BlackBox` → `TwinSpace`; the shortcut-permission hint's "for BlackBox" → "for TwinSpace" (all three locales) |
| `app/src/main/res/menu/menu_main.xml` | Removed the upstream **Open Source** (GitHub) and **Telegram Group** menu items |
| `app/src/main/java/…/view/main/MainActivity.kt` | Removed the `main_git` / `main_tg` handlers that opened the upstream GitHub repo and Telegram group |

Engine namespaces/packages **unchanged** (verified): `app` namespace `top.niunaijun.blackboxa`, `Bcore` namespace/package `top.niunaijun.blackbox`. Only the `applicationId` moved — safe because all Bcore provider authorities use the `${applicationId}` placeholder.

Not done (per PLAN §4 #6): launcher icon replacement — design pending, not needed for the first debug build. The stock icon (green Android grid background) is still in place.

## 2. Workflow security (`8a54858`)

- **Deleted** `.github/workflows/build_and_telegram.yml`. It contained a hardcoded Telegram bot credential in plaintext and shipped build artifacts to a Telegram chat. The credential value was never read, copied or reproduced anywhere in this project; a token-pattern scan over the whole working copy (filenames only) finds no match.
- **Added** `.github/workflows/build.yml` — our own clean workflow, no secrets, no Telegram step:
  checkout → Temurin **JDK 21** → Android SDK → `sdkmanager` installs `platforms;android-35`, `build-tools;35.0.0`, `ndk;29.0.13846066` → `./gradlew :app:assembleDebug` → `actions/upload-artifact` of `app/build/outputs/apk/debug/*.apk`. Triggers: `workflow_dispatch` + push to `main`. YAML parse-verified.

## 3. Split-APK fix (`930afe5`) — ported from MaxCloner

Ported from MaxCloner (branch `maxcloner`, commit `fd3f410962`, Apache-2.0) after reading its actual code. Its diff against upstream for these four files was verified to be split-APK-only, and the files were ported as-is. The Bcore diff of this project vs base is **exactly these four files**:

| File | Change |
|---|---|
| `Bcore/…/core/env/BEnvironment.java` | + `getSplitApkDir()` / `getSplitApkFile()` (per-package `splits/` dir) and `getInstalledSplitPaths()` (split paths re-derived by listing the dir at runtime) |
| `Bcore/…/core/system/pm/installer/CopyExecutor.java` | At install: resolves the host app's `splitSourceDirs` (via `BlackBoxCore.getPackageManager().getApplicationInfo(pkg, 0)`), copies **every** split into the virtual `splits/` dir (stale splits wiped first), and extracts native libs from **each** split, not just the base APK |
| `Bcore/…/core/system/pm/PackageManagerCompat.java` | Populates `ApplicationInfo.splitSourceDirs` / `splitPublicSourceDirs` for the virtual package from the installed splits dir |
| `Bcore/…/app/BActivityThread.java` | Guest class-loader dexPath = base APK + all splits (path-separated), and `nativeLibraryDir` is now passed to the `PathClassLoader` (previously omitted) |

**Deviation from PLAN §6 wording, deliberately:** PLAN listed a fifth touch-point, "`BlackBoxCore.installPackageAsUser` passing splitSourceDirs". MaxCloner's real implementation does **not** change `BlackBoxCore` at all (its `BlackBoxCore.java` is byte-identical to upstream — verified by diff): splits are resolved inside `CopyExecutor` from the host `ApplicationInfo` at install time. The port follows the real code, so `BlackBoxCore.java` is unchanged here too.

Applies at install time only: clones created before this fix would have to be deleted and re-cloned (not an issue for v1 — there are no existing clones).

**Deliberately NOT done:** MaxCloner also added its own `/data/data/<id>` path to the hidden-paths list in `Bcore/src/main/cpp/Utils/AntiDetection.cpp`. That is anti-detection code; it was flagged in the plan as an explicit user decision and the user has **not** approved it. It was skipped — no C++ file is modified in this project (verified: Bcore diff contains no `cpp/` files).

## 4. Black & white dark theme (`3a9805e`)

Palette (black & white only): background `#000000`, surface/card `#121212`, surface variant `#161616`, stroke `#2A2A2A`, primary text `#FFFFFF`, secondary text `#9E9E9E`.

| File | Change |
|---|---|
| `app/src/main/res/values/colors.xml` | Legacy colour names kept (referenced across upstream layouts) but redefined to the palette above, so the main screen, add-app list, GMS/fake-location lists and settings all follow it without layout surgery. New `ts_*` colours added (`ts_background`, `ts_surface`, `ts_surface_variant`, `ts_stroke`, `ts_text_primary`, `ts_text_secondary`, plus `black`) |
| `app/src/main/res/values/themes.xml` | `Theme.BlackBox` (style name kept — the manifest references it): parent `Theme.MaterialComponents.Light.NoActionBar` → `Theme.MaterialComponents.NoActionBar` (dark); black `colorPrimary`, **white `colorSecondary` with black `colorOnSecondary`** (this is what makes the FAB white with a black icon), black window background + status bar, white primary / grey secondary text |
| `app/src/main/res/drawable/splash.xml` | Splash background white → black |
| `app/src/main/res/layout/activity_list.xml`, `fragment_apps.xml` | Root background → `ts_background` |

## 5. Space-card home UI (`38ac656`)

**Structure choice:** the home is a `RecyclerView` of Space cards in `MainActivity`; each card hosts its grid through a **directly-bound adapter** (`RVAdapter<AppInfo>` + the existing `AppsAdapter`/`item_app.xml`), fed by a **per-space `AppsViewModel`** obtained from `ViewModelProvider` with a per-user key — the same repository/ViewModel data flow `AppsFragment` used. Child-fragment-per-card was rejected: embedding fragments inside recycled RecyclerView items is lifecycle-fragile, and the fragment's layout (full-screen `StateView` + match-parent grid) does not size inside a card. `AppsFragment` remains in the tree (unused by the home now) so nothing else breaks.

| File | Change |
|---|---|
| `app/src/main/res/layout/activity_main.xml` | Rewritten: black `ConstraintLayout`, toolbar include, `spacesRecycler` (vertical list, 96dp bottom padding for the FAB), white FAB (`backgroundTint` white, black `+` vector). ViewPager2 + dots indicator + StateView removed |
| `app/src/main/res/layout/item_space_card.xml` | **New.** `MaterialCardView` (surface `#121212`, 1dp `#2A2A2A` stroke, 16dp radius): header row = numbered badge (white circle, black number) + space name (white, bold) + ⋮ `ImageButton`; empty-state text (grey, hidden when apps exist); inner 4-column-ready `RecyclerView` (nested scrolling off so the card shows the full grid) |
| `app/src/main/res/layout/item_new_space.xml` | **New.** Outlined card with centred "+ New Space" — the upstream pager's "extra empty page" concept as a tappable card at the end of the list |
| `app/src/main/res/drawable/ts_badge_bg.xml`, `ic_more_vert.xml`, `ic_add_black.xml` | **New.** White oval badge background; white vertical-dots vector; black plus vector for the FAB |
| `app/src/main/res/menu/space_menu.xml` | **New.** Card ⋮ menu: Add App / Rename Space / Delete Space |
| `app/src/main/res/values/strings.xml` | + `add_app`, `new_space`, `space_default_name` ("Space %1$d"), `space_empty`, `space_menu`, `rename_space`, `delete_space`, `delete_space_hint`, `choose_space` |
| `app/src/main/java/…/view/main/SpacesAdapter.kt` | **New (420 lines).** Card list adapter: per-space state (ViewModel, grid adapter, cached items); badge = list position + 1; name = `Remark<userId>` preference, falling back to "Space N" (upstream's legacy `"User <id>"` default is treated as unset); ⋮ menu → Add App (picker for that space), Rename (upstream's MaterialDialog input flow writing the same `Remark<userId>` key), Delete Space (confirm dialog → `BlackBoxCore.deleteUser(userId)` + remark/order cleanup); inner grid with the existing long-press app menu (remove / clear data / stop / shortcut, same dialogs and engine calls as `AppsFragment`), drag-to-reorder via `AppsTouchCallBack` (helpers attach once per RecyclerView and resolve the owning space dynamically, because card grids recycle); the upstream deferred long-press-menu touch pattern is mirrored so drag and menu don't fight. A trailing "New Space" item opens the picker for the smallest unused userId |
| `app/src/main/java/…/view/main/MainActivity.kt` | Rewritten home logic: ViewPager/dots/subtitle-remark code removed; `initSpaces()` wires the card list + a service-available refresh callback; `onStart` refreshes; FAB → space-picker dialog (existing spaces + "New Space"; goes straight to the picker when there are no spaces); picker result → `SpacesAdapter.installIntoSpace(userId, source)`; `scanUser()` kept as a refresh alias. Storage-permission, VPN-permission, error-dialog and toolbar-menu (Settings / Fake Location) code carried over unchanged |
| `app/src/main/java/…/view/apps/AppsFragment.kt` | One call removed: it referenced `MainActivity.showFloatButton()` (the pager-era hide-on-scroll FAB), which no longer exists — replaced with an explanatory comment so the file still compiles |

Untouched, as instructed: `WelcomeActivity` / launcher flow, all engine behaviour outside §3, settings/GMS/fake-location screens (they inherit the theme via §4).

## 6. Static verification performed (no Gradle)

- All 16 changed XML files parse cleanly (Python ElementTree).
- No `BlackBox` string remains in any user-visible strings file (all locales) or menu.
- `applicationId` = `com.twinspace.cloner`; both Gradle namespaces unchanged from upstream.
- Bcore diff vs base = exactly the 4 split-APK files; no `cpp/` changes (AntiDetection untouched).
- Bot-token-pattern scan over the whole tree (filenames only): no matches; `.github/` contains only the new `build.yml`, with no Telegram references.
- Every `R.string` / `R.menu` / `R.layout` / `R.drawable` / `R.color` / `R.id` reference in the changed Kotlin files resolves to an existing resource; brace/paren balance is clean in all three changed Kotlin files.
- Full `git diff --stat` vs base eyeballed: 27 files, all inside the groups above.
- The pristine `../upstream/` clone is unmodified (`git status` clean).

**Known compile-unverified risk spots for the first CI build to catch:** the per-user-key `ViewModelProvider.get(key, class)` usage, `RVAdapter` used via direct `RecyclerView.adapter` assignment instead of its `.bind()` helper (equivalent in the fragment's usage pattern, but the library is binary-only here), and MaterialCardView attribute names. These are exactly what the first Actions build + phone test are for.

## 7. Launcher icon — Twin Squares (user pick, 2026-11-17)

The user chose **Option A — Twin Squares** from two black-and-white previews (512×512 draft in goal files `twinspace-icon-A-twin-squares.png`): a solid white rounded square behind, an outlined rounded square with a solid white centre in front, on black. Strictly #000000/#FFFFFF.

**Before:** stock Android Studio template set — legacy PNGs (green robot, `#3DDC84`) in all 6 densities (`ic_launcher.png`, `ic_launcher_round.png`, plus unreferenced `ic_launcher_beta.png` / `ic_launcher_round_beta.png` twins at the same sizes), orphan template drawables `drawable/ic_launcher_background.xml` (green grid vector) and `drawable-v24/ic_launcher_foreground.xml` (white robot vector) that **no** `mipmap-anydpi-v26` XML referenced, so no adaptive icon actually existed. Manifest already had `android:icon="@mipmap/ic_launcher"` + `android:roundIcon="@mipmap/ic_launcher_round"` (unchanged).

**Method:** raster, generated from the approved 512×512 preview with PIL/Lanczos — the preview's squircle radii and outline weights are subtle, and a vector redraw risked drifting from what the user approved; every target size is a downscale from 512, so no upscaling loss. The adaptive composite (foreground over black) was rendered and visually compared to the preview — identical geometry.

| File(s) | Change |
|---|---|
| `res/mipmap-{ldpi 36, mdpi 48, hdpi 72, xhdpi 96, xxhdpi 144, xxxhdpi 192}/ic_launcher.png` + `ic_launcher_beta.png` | Replaced: Twin Squares artwork, Lanczos-resized from the 512 master (beta twins are unreferenced anywhere in code/manifests/Gradle, replaced with the same artwork so no green stock icon remains) |
| `…/ic_launcher_round.png` + `ic_launcher_round_beta.png` (same 6 densities) | Replaced: circular-masked version (supersampled circle mask, transparent corners) of the same artwork |
| `res/mipmap-{mdpi 108, hdpi 162, xhdpi 216, xxhdpi 324, xxxhdpi 432}/ic_launcher_foreground.png` | **New.** Adaptive foreground: white glyph with alpha = preview luminance (glyph spans the same centred ~62.5% of the 108dp canvas as in the preview), transparent elsewhere |
| `res/mipmap-anydpi-v26/ic_launcher.xml`, `ic_launcher_round.xml` | **New.** Adaptive icon: background `@color/ic_launcher_background`, foreground `@mipmap/ic_launcher_foreground` (round uses the same adaptive — launchers apply their own circular mask) |
| `res/values/colors.xml` | + `ic_launcher_background` = `#000000` |
| `res/drawable/ic_launcher_background.xml`, `res/drawable-v24/ic_launcher_foreground.xml` | **Deleted.** Orphan stock template vectors (green background + robot foreground), unreferenced; superseded by the adaptive icon above |

**Verification:** every generated PNG's dimensions match its density table (checked with PIL); every pixel of the legacy/round icons and of the opaque foreground pixels is grayscale (R=G=B, worst channel diff ≤ 2 from anti-aliasing) — no `#3DDC84` or any green remains anywhere in `res/`; the new anydpi XMLs and colors.xml parse cleanly. Monochrome/themed-icon layer skipped (raster foreground route; noted as a possible later addition). **Flag:** `res/drawable/splash.xml` references `@mipmap/ic_launcher` centred on black — it picks up the new icon automatically, no splash change made. Compile-unverified, like the rest of v1 — first check is the Dec-1 Actions build.

## 8. Clone-time GMS prompt (`436fa9e`, 2026-11-19)

**User spec:** the manual per-space install in Settings → GMS Manager stays exactly as it was. ADDITIONALLY, when an app is cloned into a space, if that space does not already have Google services AND the host phone supports GMS, a dialog asks whether to install Google services (Play Store, Google Play Services, Google Services Framework) into that space. Yes → install; No → nothing. **No silent auto-install anywhere** — installation only ever starts from the user's tap (here, or in the GMS Manager as before).

**Clone flow found (app module):** there is exactly one user-facing install funnel. Home (card ⋮ "Add App" or the FAB space-picker) → `MainActivity.onAddApp(userId)` → `ListActivity` (picker only — it returns a `source` string: a package name, or a `content://` URI from its "choose APK file" menu item; both paths return through the same result) → `MainActivity.apkPathResult` → `SpacesAdapter.installIntoSpace(userId, source)` → per-space `AppsViewModel.install(source, userId)` → (IO dispatcher, via `BaseViewModel.launchOnUI`) `AppsRepository.installApk(...)` → `BlackBoxCore.installPackageAsUser(...)`. The one app-module call site of `installPackageAsUser` is `AppsRepository.installApk`.

**Hook point and why:** the repository's existing `resultLiveData` carries only a rendered message string and is shared with uninstall/clear, so install success and the cloned package name are unrecoverable downstream. Instead of parsing strings, a dedicated success signal was added alongside it:

| File | Change |
|---|---|
| `app/…/data/AppsRepository.kt` | `installApk(...)` gains an optional `cloneSuccessLiveData: MutableLiveData<Pair<Int, String>>? = null` parameter; on `installResult.success` only, it posts `(userId, installResult.packageName)` after the existing result post. Failure/exception paths post nothing |
| `app/…/view/apps/AppsViewModel.kt` | + `cloneSuccessLiveData`; `install()` passes it to the repository |
| `app/…/util/GmsPrompt.kt` | **New.** `maybePrompt(activity, userId, clonedPackage)`: skips Google packages (below), then on a background thread checks `BlackBoxCore.isSupportGms() && !isInstallGms(userId)` and posts back to the main thread to show a MaterialDialog in the GMS Manager's own style (`title`/`message`/`positiveButton`/`negativeButton` with string resources). Install runs `BlackBoxCore.installGms(userId)` on a background thread after a `gms_installing` toast, then toasts `install_success` / `install_fail` / `install_fail_no_msg` (all pre-existing strings) on the main thread. The dialog is not shown if the activity `isFinishing || isDestroyed` |
| `app/…/view/main/SpacesAdapter.kt` | Observes each space ViewModel's `cloneSuccessLiveData`: consumes the event (sets the LiveData value back to `null`, so observer re-attach/state recreation cannot re-fire it), then calls `GmsPrompt.maybePrompt(...)`. Batch guard: `SpaceState.gmsPromptHandled` — reset in `installIntoSpace()` (a new picker session), set when an event is handled, so one clone operation prompts at most once. The flag is deliberately **not persisted**: tapping "Not now" saves nothing, and the next clone into a still-GMS-less space prompts again (intended v1 behaviour) |
| `app/src/main/res/values/strings.xml` | + `gms_prompt_title` ("Google services"), `gms_prompt_message`, `gms_prompt_install` ("Install"), `gms_prompt_not_now` ("Not now"), `gms_installing`. English in `values/` only — the same convention the other v1-added strings (`new_space`, `choose_space`, …) follow; the zh locales were not touched |

**Skip conditions:** the cloned package is itself a Google app/service package — the helper duplicates `GmsCore.GOOGLE_APP` + `GOOGLE_SERVICE` from `:Bcore` (those sets are private to the engine): `com.android.vending`, Play Games, the wearable apps, `com.google.android.gms`, `com.google.android.gsf`, `com.google.android.gsf.login`, backup/transport, configupdater, sync adapters, feedback, onetimeinitializer, partnersetup, setupwizard. Also skipped when the host does not support GMS, when the space already has GMS, when the clone failed, or when any engine check throws (logged, treated as "do not prompt").

**Deliberately left unhooked:** the legacy `AppsFragment.installApk()` path uses the same `AppsViewModel.install()`, so its clones now also post to `cloneSuccessLiveData` — but `AppsFragment` does not observe that LiveData and shows no prompt. The fragment is not reachable from the TwinSpace card home (kept compiling for compatibility, see §5), so the prompt covers the real clone flow; wiring the fragment too was judged dead UI surface. `FakeManagerActivity`'s identically-named `source` result is a fake-location file pick, not an install — untouched.

**Engine (`:Bcore`) untouched** — app module only, verified by an empty `git diff` for `Bcore/`.

**Verification (static, no Gradle):** changed `strings.xml` parses (Python ElementTree; zh locales re-parsed too, unchanged); every `R.string.*` referenced from the new/changed Kotlin resolves to a defined string; brace/paren balance clean in all four Kotlin files; full diff eyeballed (5 files, +47/−3 plus the new 110-line helper). **Compile-unverified**, like the rest of TwinSpace — first check is the first Actions build. One noted risk spot of the same class as §6: the consume-and-clear `cloneSuccessLiveData.value = null` assignment inside its own observer (the codebase already relies on the same platform-type behaviour with `postValue(null)` in `ListActivity`).
