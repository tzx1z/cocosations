#!/bin/sh
# Проверяет подписанные APK в образе сборки: сертификат release-ключа и перенос
# подписи на неподписанный APK через apksigcopier. Так же F-Droid сверяет свою
# сборку с APK по ссылке binary: из рецепта.
# Использование: check.sh <каталог подписанных APK> <каталог неподписанных APK>
set -eu

signed=${1:?}
unsigned=${2:?}
# он же AllowedAPKSigningKeys в рецепте F-Droid
expected=015df71201ff6e6ca0db7b666bd4ae4fb24386199e80e4475f70a4c6233eb78f

count=0
for apk in "$signed"/*.apk; do
    name=${apk##*/}
    cert=$("$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --print-certs "$apk" 2>/dev/null \
        | sed -n 's/^Signer #1 certificate SHA-256 digest: //p') || true
    if [ "$cert" != "$expected" ]; then
        echo "$name: сертификат ${cert:-не найден}, ожидается $expected" >&2
        exit 1
    fi
    apksigcopier copy "$apk" "$unsigned/$name" /tmp/copy.apk
    cmp "$apk" /tmp/copy.apk
    rm /tmp/copy.apk
    count=$((count + 1))
done

total=$(find "$unsigned" -maxdepth 1 -name '*.apk' | wc -l)
if [ "$count" -ne "$total" ]; then
    echo "подписанных APK $count, неподписанных $total" >&2
    exit 1
fi
echo "APK: $count, сертификат и перенос подписи совпадают"
