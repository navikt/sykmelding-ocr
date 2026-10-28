#!/bin/bash
set -eo pipefail

# Promotes the draft release created by create-release.sh to a real, published
# release marked as latest. Only run this after the Gradle modules have been
# published successfully.

NEW_TAG=${NEW_TAG:-v$(cat lib/version)}

if [ -z "${NEW_TAG#v}" ]; then
  echo "No version available, run scripts/next-version.sh first" >&2
  exit 1
fi

echo "Publishing release $NEW_TAG"

gh release edit "$NEW_TAG" --draft=false --latest

if [ -n "$GITHUB_STEP_SUMMARY" ]; then
  echo "✅ Release $NEW_TAG published and marked as latest" >> "$GITHUB_STEP_SUMMARY"
fi
