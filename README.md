# KatDrama — CloudStream Extension

CloudStream extension for **KatDrama** (`https://new2.katdrama.my`) — K-Dramas, C-Dramas and Hindi Dubbed series.

## What it does

- **Home:** Latest dramas + categories (Action, Comedy, Romance, Hindi Dubbed, Movies), 12 per page.
- **Search:** `?q=` search on the site.
- **Episodes:** Parses the drama page and lists episodes (E01, E02, …) with 1080p / 720p / 480p file links.
- **Play:** Resolves episodes through the site's `links.kmhd.me` player to **Streamtape** and **Streamwish** video links (CloudStream's built-in extractors).

## How it works (technical)

KatDrama is a SvelteKit SPA. Its data is embedded in the HTML as a JS object literal with unquoted keys, so the provider:

1. Fetches `https://new2.katdrama.my/?page=N` and parses the `items:[...]` array from the `__sveltekit_*.resolve(...)` payload (title, slug, TMDB poster). Pagination is `?page=N` (102 pages).
2. Fetches `https://new2.katdrama.my/<slug>/` and parses `post_content` HTML for the "Episode NN" headings + the `links.kmhd.me/file/<id>` quality links, plus the `links.kmhd.me/play?id=<playId>` Watch Online link.
3. For an episode, fetches `https://links.kmhd.me/play?id=<playId>` and parses its `info:{<fileId>:{streamtape_res, streamwish_res}}` object, then hands `https://streamtape.com/e/<code>` and `https://hglink.to/e/<code>` to CloudStream's extractors.

Only some episodes have Streamtape/Streamwish links uploaded (the site adds them progressively); the provider tries 1080p, then 720p, then 480p file ids.

## Build (APK / .cs3)

**Option A — GitHub Actions (easiest, no PC setup):**

1. Create a new public GitHub repo and push this whole folder to the `main` (or `master`) branch.
2. Create a branch named `builds` (empty is fine): `git checkout --orphan builds && git commit --allow-empty -m "builds" && git push origin builds`.
3. Push to `main` — the workflow in `.github/workflows/build.yml` runs, builds the plugin and pushes the `.cs3` file to the `builds` branch.
4. Download the `.cs3` from the `builds` branch and add it in CloudStream: **Settings → Extensions → Add repository** (or install the `.cs3` directly).

**Option B — Android Studio:**

1. Open this folder in Android Studio (JDK 17).
2. Let Gradle sync, then run `./gradlew :KatDramaProvider:make` (or the `make` task) to build the `.cs3`.
3. The `.cs3` appears in `KatDramaProvider/build/`.

## Install in CloudStream

- Copy the `.cs3` file to your phone and open it with CloudStream, or add the repo URL in CloudStream's Extensions settings. The provider appears as **KatDrama**.

## Notes

- The site's download links (`links.kmhd.me/file/<id>`) go through a "locked" interstitial page, so this extension uses the **Watch Online** player (`links.kmhd.me/play?id=`) for streaming instead.
- If the site changes its domain, update `mainUrl` in `KatDramaProvider.kt`.
- Version: 1. Author: Toji.
