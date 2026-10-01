#!/bin/sh
# published-tree-changed.sh <groupId:artifactId> <tree>
#
# THE CHANGE GATE for a tree this repository publishes as an artifact (epic qits-546): does <tree>,
# a repository-relative directory, differ byte-for-byte from the same directory inside the NEWEST
# PUBLISHED version of the artifact? The artifact carries the tree under the same relative path —
# `golden-masters/` in the repository is `golden-masters/` in the jar.
#
# It answers on stdout with exactly one line, and exits 0:
#
#     first                nothing is published yet (the coordinate's maven-metadata.xml is a 404)
#     changed <latest>     the tree differs from <latest>'s (a per-file diff goes to stderr)
#     unchanged <latest>   byte-identical, file for file, to <latest>'s
#
# EVERY OTHER OUTCOME EXITS NON-ZERO, loudly, and never decides: a 5xx, a 401, an unreachable
# registry, an unparseable metadata document, a jar that will not unpack. A gate that read an error
# as "unchanged" would skip a publish in silence; one that read it as "changed" would hide the
# outage behind a deploy. The caller does `decision=$(…)` under `set -e`, so an error is a red step.
#
# THE PUBLISHED ARTIFACT IS THE BASELINE, NOT THE PREVIOUS GIT TAG. A publish that failed once must
# be retried by the next release rather than skipped forever because the tree "did not change since
# the last tag". And a re-run of a release that already deployed reads its own version back as
# <latest> and answers `unchanged`, which is the already-published skip for free.
#
# A version directory with no jar in it (a deploy that died between files) is `changed`: that
# version is not a published artifact, so the tree has not been published.
#
# Deciding only — the deploy stays in the release step that calls this. Slice two (the same tree as
# an npm tarball) adds an npm arm to "resolve and unpack the newest artifact"; the comparison below
# is shared and does not care where the unpacked tree came from.
#
# ENVIRONMENT
#   QITS_MAVEN_REGISTRY_URL  the maven repository root, as every release step already has it
#   QITS_TOKEN               optional; sent as a bearer when set (the public edge refuses an
#                            anonymous read), omitted when not — never an empty bearer
#
# Portable sh: runs on busybox (maven-base is alpine) and needs curl, sha256sum, find, sort, and
# one of unzip / the JDK's jar / python3 to unpack.
set -eu

die() {
  echo "published-tree-changed: $*" >&2
  exit 1
}

[ "$#" -eq 2 ] || die "usage: $0 <groupId:artifactId> <tree>"
coordinate=$1
tree=$2

case "$coordinate" in
  *:*:*|:*|*:) die "'$coordinate' is not a groupId:artifactId coordinate" ;;
  *:*) ;;
  *) die "'$coordinate' is not a groupId:artifactId coordinate" ;;
esac
group=${coordinate%%:*}
artifact=${coordinate#*:}

# The tree is a path inside the repository and inside the artifact, so it is relative and points
# downwards; `./x/` and `x` are one tree.
tree=${tree#./}
while [ "${tree%/}" != "$tree" ]; do tree=${tree%/}; done
case "$tree" in
  ''|/*|..|../*|*/..|*/../*) die "the tree '$2' must be a relative, downward path" ;;
esac
[ -d "$tree" ] || die "no directory at $tree"
[ -n "$(find "$tree" -type f | head -1)" ] || die "$tree holds no files — refusing to judge an empty tree"

root=${QITS_MAVEN_REGISTRY_URL:?QITS_MAVEN_REGISTRY_URL is the maven repository to compare against}
root=${root%/}
coordinate_url="$root/$(printf '%s' "$group" | tr . /)/$artifact"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# GET <url> into <file>, printing the HTTP status. A transport failure (no answer at all) is fatal
# here rather than a status, because there is nothing to decide on.
get() {
  if [ -n "${QITS_TOKEN:-}" ]; then
    curl -sS -L --retry 2 --retry-delay 1 -o "$2" -w '%{http_code}' \
      -H "Authorization: Bearer $QITS_TOKEN" "$1" || die "cannot reach $1"
  else
    curl -sS -L --retry 2 --retry-delay 1 -o "$2" -w '%{http_code}' "$1" || die "cannot reach $1"
  fi
}

# --- the newest published version ------------------------------------------------------------
status=$(get "$coordinate_url/maven-metadata.xml" "$work/metadata.xml")
case "$status" in
  200) ;;
  404) echo "first"; exit 0 ;;
  *) die "GET $coordinate_url/maven-metadata.xml answered HTTP $status — not deciding on an error" ;;
esac
# One element per line whatever the document's layout: split on '<' so each line starts with a tag.
elements=$(tr '<' '\n' < "$work/metadata.xml")
latest=$(printf '%s\n' "$elements" | sed -n 's/^latest>[[:space:]]*\([^[:space:]]*\)[[:space:]]*$/\1/p' | head -1)
[ -n "$latest" ] || latest=$(printf '%s\n' "$elements" | sed -n 's/^release>[[:space:]]*\([^[:space:]]*\)[[:space:]]*$/\1/p' | head -1)
[ -n "$latest" ] || latest=$(printf '%s\n' "$elements" | sed -n 's/^version>[[:space:]]*\([^[:space:]]*\)[[:space:]]*$/\1/p' | tail -1)
case "$latest" in
  '') die "$coordinate_url/maven-metadata.xml names no <latest>, <release> or <version>" ;;
  *[!A-Za-z0-9._+-]*) die "$coordinate_url/maven-metadata.xml names a version that is not one: '$latest'" ;;
esac

# --- that version's jar, unpacked ----------------------------------------------------------------
jar_url="$coordinate_url/$latest/$artifact-$latest.jar"
status=$(get "$jar_url" "$work/published.jar")
case "$status" in
  200) ;;
  404)
    echo "$coordinate $latest is listed but has no jar ($jar_url) — not a published artifact" >&2
    echo "changed $latest"
    exit 0 ;;
  *) die "GET $jar_url answered HTTP $status — not deciding on an error" ;;
esac
mkdir "$work/published"
if command -v unzip > /dev/null 2>&1; then
  (cd "$work/published" && unzip -q ../published.jar) || die "cannot unpack $jar_url"
elif command -v jar > /dev/null 2>&1; then
  (cd "$work/published" && jar xf ../published.jar) || die "cannot unpack $jar_url"
elif command -v python3 > /dev/null 2>&1; then
  python3 -m zipfile -e "$work/published.jar" "$work/published" || die "cannot unpack $jar_url"
else
  die "no unzip, jar or python3 on this image to unpack $jar_url with"
fi

# --- the comparison: path and sha256 of every file, in byte order --------------------------------
# Files only: directory entries and empty directories are not content.
manifest() {
  if [ -d "$1" ]; then
    (cd "$1" && find . -type f -exec sha256sum {} + | LC_ALL=C sort -k 2)
  fi
}
manifest "$tree" > "$work/local.sha256"
manifest "$work/published/$tree" > "$work/published.sha256"
if cmp -s "$work/local.sha256" "$work/published.sha256"; then
  echo "unchanged $latest"
else
  echo "$tree differs from $coordinate $latest (- published, + this tree):" >&2
  diff "$work/published.sha256" "$work/local.sha256" >&2 || true
  echo "changed $latest"
fi
