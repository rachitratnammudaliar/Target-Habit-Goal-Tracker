#!/usr/bin/env bash
# Builds an unsigned Target.ipa for sideloading (AltStore, Sideloadly, or your own Apple ID in Xcode).
# Usage: ios/build-ipa.sh <version> <output-dir>
set -euo pipefail
VERSION=${1:?version}
OUT=${2:?output dir}
cd "$(dirname "$0")"
xcodegen generate
xcodebuild -project Target.xcodeproj -scheme Target -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath build \
  MARKETING_VERSION="$VERSION" CURRENT_PROJECT_VERSION="${GITHUB_RUN_NUMBER:-1}" \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" build
rm -rf Payload
mkdir Payload
cp -R build/Build/Products/Release-iphoneos/Target.app Payload/
mkdir -p "$OUT"
zip -qry "$OUT/Target-$VERSION-iOS.ipa" Payload
rm -rf Payload
echo "Built $OUT/Target-$VERSION-iOS.ipa"
