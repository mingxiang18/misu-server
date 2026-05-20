#!/bin/sh
# Wrapper around /kaniko/executor-real to work around Alpine 3.20 + Kaniko 1.23.2
# incompatibility. Inside Kaniko's snapshot phase /lib state gets corrupted such
# that `apk add` fails with libcrypto symbol relocation errors. Two fixes:
#
# 1) Substitute the base image: amazoncorretto:17-alpine3.20-jdk → :17-alpine3.20-jdk-fonts
#    (the -fonts variant was prebuilt on the Mac with ttf-dejavu/fontconfig already
#    installed, so no need to invoke apk inside Kaniko).
# 2) Comment out any `RUN ... apk ... add ...` line in the Dockerfile, since the
#    fonts are already present.
#
# Also force --use-new-run=false --snapshot-mode=full because Kaniko's experimental
# new run + redo snapshot mode is the actual trigger of the /lib corruption.
set -e

NEW_ARGS=""
for arg in "$@"; do
  case "$arg" in
    --dockerfile=*)
      orig="${arg#--dockerfile=}"
      mod="/tmp/Dockerfile.kaniko.$$"
      # 1) FROM → fonts 变体（base 含 ttf-dejavu/fontconfig，无需再 apk）
      # 2) 删整个多行 RUN apk 块（从 "RUN sed ... apk ... repositories \" 到 "echo timezone"）
      #    单行 sed 抓不到多行 RUN，所以用 line-range 删除
      sed -e 's|^\(FROM .*amazoncorretto:17-alpine3.20-jdk\)$|\1-fonts|' \
          -e '/^RUN sed.*apk.*repositories/,/timezone[" ]*$/d' \
          "$orig" > "$mod"
      echo "[kaniko-wrapper] modified Dockerfile saved to $mod:" >&2
      cat "$mod" >&2
      NEW_ARGS="$NEW_ARGS --dockerfile=$mod"
      ;;
    *)
      NEW_ARGS="$NEW_ARGS $arg"
      ;;
  esac
done

exec /kaniko/executor-real $NEW_ARGS --use-new-run=false --snapshot-mode=full
