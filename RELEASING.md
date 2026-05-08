# Releasing

## Version Convention

- Development: `X.Y.Z.SNAPSHOT`
- Release: `X.Y.Z.RELEASE`

## Steps

1. Update `pom.xml` version: `X.Y.Z.SNAPSHOT` -> `X.Y.Z.RELEASE`
2. Commit: `git commit -am "Release X.Y.Z.RELEASE"`
3. Tag: `git tag vX.Y.Z.RELEASE`
4. Push: `git push && git push origin vX.Y.Z.RELEASE`
5. Bump to next snapshot: update version to `X.Y.(Z+1).SNAPSHOT`, commit, push

## Dependency Order

If releasing both `chart-parser` and `pdf-importer`:

1. Release `chart-parser` first
2. Update `pdf-importer` chart-parser dependency version
3. Release `pdf-importer`

## What Happens Automatically

- **Push to master** -> CI runs tests; Publish SNAPSHOT deploys (only if version is `.SNAPSHOT`)
- **Push a `v*` tag** -> Builds, deploys to GitHub Packages, creates a GitHub Release with the fat JAR attached
