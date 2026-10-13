# Getting this onto GitHub and onto your TV

One-time setup. After this, every tagged release builds itself and appears on the install page
automatically.

---

## 1. Create the repository

On [github.com/new](https://github.com/new):

- **Name:** `opentv`
- **Visibility:** Public — GitHub Pages and Actions are free on public repos, and the whole
  point of the project is that people can read it
- **Do not** tick "Add a README", ".gitignore" or "Choose a license" — this repo already has
  all three, and an initialised repo makes the first push awkward

## 2. Push it

From the unzipped `opentv` folder:

```bash
cd opentv
git remote add origin https://github.com/opentvproject/opentv.git
git push -u origin main
```

The repo already has an initial commit, so there is nothing to stage.

If git asks for a password, it wants a **personal access token**, not your account password —
GitHub stopped accepting passwords over HTTPS. Generate one at
**Settings → Developer settings → Personal access tokens → Tokens (classic)** with the `repo`
scope, and paste that when prompted.

## 3. Funding links (optional)

The "Sponsor" button on the repo comes from `.github/FUNDING.yml` — it points at GitHub Sponsors
and a PayPal donate link. Edit that file to change them. Everything else already points at
`opentvproject/opentv`.

## 4. Turn on Pages

**Settings → Pages → Build and deployment → Source: GitHub Actions.**

That's the only setting. Don't pick "Deploy from a branch" — the workflow in
`.github/workflows/pages.yml` handles it.

Your install page will be at:

```
https://opentvproject.github.io/opentv/
```

It goes live a minute or two after the first push.

## 5. Cut the first release

This is what actually produces an installable APK:

```bash
git tag v0.1.0
git push origin v0.1.0
```

Watch it under the **Actions** tab. It runs the tests, builds the APK, and attaches it to a
GitHub release. The install page picks it up on its own — nothing to edit.

**Expect the first run to fail.** This code has never been compiled; there was no Android SDK
available where it was written. The build log will name the file and line of anything that
needs fixing. Fix, commit, delete and re-push the tag:

```bash
git tag -d v0.1.0 && git push origin :refs/tags/v0.1.0
git tag v0.1.0 && git push origin v0.1.0
```

## 6. Install it on the TV

Open the install page and follow the steps there. The short address to type into the
**Downloader** app is:

```
opentvproject.github.io/opentv/apk
```

That link always redirects to the newest APK, so it never needs changing between releases.

---

## Before you tell anyone about it

Two things worth doing first:

1. **Set up real signing.** See `docs/RELEASING.md`. Right now the APK is signed with the
   Android debug key so it can be installed at all. Once you switch to a real keystore, anyone
   running a debug-signed build has to uninstall and reinstall — trivial while it's just you,
   genuinely annoying once it's a few hundred people.

2. **Test it yourself for a week.** The thing that damaged Viewella's reputation was not bugs;
   every new app has those. It was people paying, then finding nobody was home. You are not
   charging anyone, which removes most of that risk — but arriving on r/Viewella with something
   that falls over on first run still spends goodwill you only get to spend once.
