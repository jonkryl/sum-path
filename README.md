# Sum Path · Сумма-путь

An original offline Android number-route puzzle by jonkryl. Package: `com.jonkryl.sumpath`. Android7+ (min24), compile/target36. Native touch interface in English and Russian. Contact: jonkryl@gmail.com.

Start at S, then tap orthogonally adjacent cells. Each visited value, including the start and finish, contributes to the sum. Collect every ◆ key and reach F at exactly the target. Cells cannot be repeated and unused cells are allowed. **Any valid path wins**; the game never compares the player's route with a generator answer. Undo removes one step and Restart clears the route on the same puzzle.

The first launch opens an authored, step-by-step tutorial. Play offers First steps4×4, Winding paths4×4, and Deep routes5×5. New puzzles use different deterministic level seeds. Daily uses one stable5×5 task per local calendar date. Each difficulty and daily/tutorial mode retains its own route. Progress and personalization choice remain in private device storage, with Android backup disabled. There is no account, cloud service, purchase or time penalty.

## Genuine bounded search and generation

`core/PuzzleSolver.kt` implements DFS over simple paths with exact sum/key validation, positive-sum pruning, reachability checks that cannot pass through the finish, and safe lower bounds. A node limit is distinct from UNSOLVABLE; cancellation and optional time limits are explicit statuses.

`core/PuzzleGenerator.kt` uses its own specified SplitMix64 stream, builds a random simple path under4,000nodes/8attempts, assigns positive values and keys, derives a solvable target, and then proves the candidate with the actual solver (100,000node budget). It never returns an empty or invalid board on a limit. A checked, transformed authored fixture is used as a bounded fallback and is solved again under a10,000node budget. Generation runs on a worker thread; the UI stays responsive. Node limits, rather than wall-clock timing, preserve stable boards across devices.

16core unit cases cover750seeds across all3difficulties/both sizes, an independent solution oracle, an independent exhaustive oracle on120small-target boards, a hand-authored board with two winning routes, invalid sum/key/transition/repeated cells, node/time/cancel statuses, forced fallback, undo/reset, complete snapshot serialization and golden daily/level seeds. Ad retry tests cover single inflight requests and bounded recovery.

## Advertising and privacy

Yandex Mobile Ads8.5.0, isolated bottom banner only. Debug uses the SDK demo banner. Release requires this product's real `R-M` through `YANDEX_BANNER_ID`; missing/demo IDs prevent release building. The SDK initializes only after persisted consent (defaultfalse), location trackingfalse and app-ad analytics reportingfalse are configured. Both personalization choices retain ad requests. AD_ID permission is removed. Errors/no-fill do not change puzzles; retries use15s/45s/5min cooldown with one inflight request and stop in background. Ad placement has its own footer, separated from game actions.

Privacy source: `docs/privacy/index.html`. Intended public URL: https://jonkryl.github.io/sum-path/privacy/ . Public Play and app-level RSYA activation must be verified separately; a GitHub release or active ad block does not establish public production or paid serving.

## Verification and protected release

- `Android CI`: JUnit, Android lint, debug/test APKs; real API24/36 emulator taps through tutorial, generated puzzles, new task/difficulty, daily, undo/reset, force-stop restore and real200%font scale. Device screenshots and runner reports are retained. APK hashes are bound to the checked-out source SHA before device installation.
- `Signed Android release`: full checks, protected upload key in ephemeral runner storage, release lint, APK/AAB signatures, upload certificate, package/version/min/target, bundle validation and16KBAPK/AABnative/ZIPalignment. Exact artifact hashes, real R-M and source/run provenance are saved. No release ad requests are made by CI.

Own signing material stays outside source. GitHub secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`; repository variable `YANDEX_BANNER_ID`. No keystore, credential, local Gradle cache, build output or emulator image belongs in Git.

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

The repository's workflows are authoritative for final release gates. Store declarations must reflect the final merged manifest and actual advertising SDK data behavior.
