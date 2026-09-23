#!/bin/bash
set -eo pipefail

# Computes the next semantic version based on the latest version tag and the
# body of the last commit (MAJOR / MINOR / anything else -> patch).
#
# Side effects:
#   * writes the new version to lib/version (so it is baked into the build)
#   * exports NEW_VERSION / NEW_TAG / LAST_TAG to $GITHUB_ENV when available
#
# This must run BEFORE `gradle build` so the artifacts carry the version that
# is actually going to be released. `create-release.sh` then only tags and
# publishes, without touching the version again.

LAST_TAG=$(git tag --sort=-creatordate | grep -E "^v[0-9]+\.[0-9]+\.[0-9]+$" | head -n 1 || true)

case "$(git log -1 --pretty=format:"%b")" in
  MAJOR) BUMP="major" ;;
  MINOR) BUMP="minor" ;;
  *)     BUMP="patch" ;;
esac

if [ -z "$LAST_TAG" ]; then
  MAJOR=0; MINOR=0; PATCH=0
else
  IFS='.' read -r MAJOR MINOR PATCH <<< "${LAST_TAG#v}"
fi

case "$BUMP" in
  major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
  minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
  patch) PATCH=$((PATCH + 1)) ;;
esac

NEW_VERSION="${MAJOR}.${MINOR}.${PATCH}"
NEW_TAG="v${NEW_VERSION}"

echo -n "$NEW_VERSION" > lib/version
echo "Set version $NEW_VERSION in lib/version (bump: $BUMP, previous: ${LAST_TAG:-none})"

if [ -n "$GITHUB_ENV" ]; then
  {
    echo "LAST_TAG=$LAST_TAG"
    echo "NEW_VERSION=$NEW_VERSION"
    echo "NEW_TAG=$NEW_TAG"
  } >> "$GITHUB_ENV"
fi
