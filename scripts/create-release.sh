#!/bin/bash
set -eo pipefail

# Creates the git tag and a DRAFT GitHub release for the version already
# written to lib/version by next-version.sh. It never changes the version, so
# the artifacts built and tested earlier in the pipeline are exactly what gets
# released.
#
# The release stays a draft until the modules have been published successfully;
# scripts/publish-release.sh then promotes it to the latest release.

if [ ! -s lib/version ]; then
  echo "lib/version is missing or empty, run scripts/next-version.sh first" >&2
  exit 1
fi

NEW_VERSION=$(cat lib/version)

if ! echo "$NEW_VERSION" | grep -qE "^[0-9]+\.[0-9]+\.[0-9]+$"; then
  echo "lib/version contains '$NEW_VERSION', expected a version like 1.2.3" >&2
  exit 1
fi

NEW_TAG="v${NEW_VERSION}"

if git rev-parse -q --verify "refs/tags/$NEW_TAG" >/dev/null; then
  echo "Tag $NEW_TAG already exists" >&2
  exit 1
fi

LAST_TAG=${LAST_TAG:-$(git tag --sort=-creatordate | grep -E "^v[0-9]+\.[0-9]+\.[0-9]+$" | head -n 1 || true)}

RELEASE_NOTES=$(git log ${LAST_TAG:+$LAST_TAG..HEAD} --pretty=format:"* %s")

echo "Releasing $NEW_TAG (draft)"
echo "$RELEASE_NOTES"

git tag "$NEW_TAG"
git push origin "$NEW_TAG"

gh release create "$NEW_TAG" -t "$NEW_TAG" -n "$RELEASE_NOTES" --draft

if [ -n "$GITHUB_ENV" ]; then
  echo "NEW_TAG=$NEW_TAG" >> "$GITHUB_ENV"
fi

if [ -n "$GITHUB_STEP_SUMMARY" ]; then
  {
    echo "## Draft release $NEW_TAG"
    echo ""
    echo "$RELEASE_NOTES"
  } >> "$GITHUB_STEP_SUMMARY"
fi
