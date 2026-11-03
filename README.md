# cs3-anilab

CloudStream extension: Anilab catalog today, Kyoto streams next.

## install (users)

1. push this repo to github, create an empty `builds` branch
2. pushing to `main` auto-builds the `.cs3` into the `builds` branch
3. in CloudStream app: Settings > Extensions > Add repository, paste
   `https://raw.githubusercontent.com/aor-rex/cloudstream-extension/builds/plugins.json`
4. install the Anilab extension from the list

## build locally

needs Android SDK + JDK 17, then `./gradlew make`

## status

- main page, search, episode list: live against `anilab2.amdapi.click/api`
- streams (`loadLinks`): Kyoto HMAC signer ported to pure kotlin in
  `AnilabProvider.kt` (`object Kyoto`), handshake against live server
  still being locked — returns empty until confirmed
