#!/usr/bin/env bash
# Guards against SHIZUKUPLUS-#422: a themed vector drawable (android:tint="?attr/...")
# used as a NotificationCompat small/action icon crashes with
# android.app.RemoteServiceException: Bad notification... Couldn't create icon
# on some OEM builds (observed on OneUI 3.1/Android 11), because the system
# renders status-bar icons in its own theme context, which may not resolve the
# app's theme attribute. Notification icons must be untinted (system applies
# its own tint at render time).
set -euo pipefail
cd "$(dirname "$0")/../.."

fail=0
while IFS=: read -r file line rest; do
    name=$(sed -n "${line}p" "$file" | grep -oP '(?:setSmallIcon|\.addAction)\(\s*R\.drawable\.\K[A-Za-z0-9_]+')
    [[ -z "$name" ]] && continue
    drawable=$(find . -path '*/res/drawable*' -iname "${name}.xml" | head -1)
    if [[ -z "$drawable" ]]; then
        continue # not a local vector drawable (e.g. resolved via a different module we didn't scan)
    fi
    if grep -q 'android:tint=' "$drawable"; then
        echo "ERROR: $file:$line references '$name', which declares android:tint in $drawable"
        echo "       Notification icons must not carry a theme tint - create an ic_notification_* variant instead."
        fail=1
    fi
done < <(grep -rnE '\.setSmallIcon\(\s*R\.drawable\.|\.addAction\(\s*R\.drawable\.' --include='*.kt' --include='*.java' manager/src/main/java)

if [[ "$fail" -ne 0 ]]; then
    echo
    echo "check-notification-icons.sh: found tinted drawables used as notification icons (see above)."
    exit 1
fi
echo "check-notification-icons.sh: OK"
