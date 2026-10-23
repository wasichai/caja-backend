# Releases

Igual que wasichai y srtm-backend: [release-please](https://github.com/googleapis/release-please) sobre Conventional
Commits.

## Flujo

1. Se mergean commits convencionales (`feat:`, `fix:`, ...) a `main`.
2. `release-please.yml` corre en cada push a `main` y abre (o actualiza) un PR de release que sube `version=` en
   `gradle.properties` y escribe `CHANGELOG.md`.
3. Al mergear ese PR, release-please crea el tag `vX.Y.Z` y el GitHub Release.
4. El release dispara `release.yml`: compila `./gradlew bootJar` con la versión del tag y adjunta
   `caja-backend-X.Y.Z.jar` y su `.sha256` al release.

## Primer release: 0.1.0

La línea parte de `0.0.0` (manifest y `gradle.properties`). Mientras la versión sea 0.x, un `feat:` sube el minor y un
`fix:` el patch (`bump-minor-pre-major`), así que el primer PR de release propone 0.1.0. Para fijar otra versión,
`"release-as": "X.Y.Z"` en `release-please-config.json`, y **quitarlo después de mergear ese PR**: si no, cada PR
siguiente volvería a proponer la misma versión. Un cambio que rompe (`!` o `BREAKING CHANGE:`) sube el minor en 0.x y
el major desde 1.x.

## Secreto (una vez)

`RELEASE_PLEASE_TOKEN`: PAT fine-grained sobre este repo con Contents y Pull requests en lectura/escritura. Un
release creado con el `GITHUB_TOKEN` del workflow no dispara otros workflows, y sin PAT `release.yml` no correría.
Sin el secreto, `release-please.yml` usa `GITHUB_TOKEN` (requiere Settings → Actions → General → permitir que
Actions cree PRs) y el jar habría que adjuntarlo a mano.
