#!/system/bin/sh
# ShizukuX force-dark watch daemon.
#
# Runs detached from the app process on purpose: MIUI freezes the app (including
# its foreground service) once it is in the background, which stops every
# in-process polling loop. This script is started through Shizuku's root shell,
# survives app freezing, and is the only reliable driver for the system night
# mode switch.
#
# Input  : force_dark.conf   (written by the app)
# Output : force_dark.state  (read by the app)
#          force_dark.log    (rotating diagnostics)

D=/data/local/tmp/shizukux
CONF=$D/force_dark.conf
STATE=$D/force_dark.state
PIDF=$D/force_dark.pid
LOG=$D/force_dark.log

mkdir -p $D 2>/dev/null

# Re-exec into its own session and answer immediately. The caller is a short-lived
# Shizuku shell whose process the app destroys as soon as it drops the handle, and a
# plain background child goes down with that destroy - measured here: the script was
# launched, never wrote its log, and no process survived. With this hop the pid the
# caller can kill is the short-lived shell, and the watcher below has its own session.
if [ "$1" != "--serve" ]; then
  if command -v setsid > /dev/null 2>&1; then
    setsid sh "$0" --serve > /dev/null 2>&1 &
  else
    nohup sh "$0" --serve > /dev/null 2>&1 &
  fi
  exit 0
fi

log() {
  echo "$(date +%H:%M:%S) $*" >> $LOG 2>/dev/null
  lines=$(wc -l < $LOG 2>/dev/null)
  if [ "$lines" -gt 400 ] 2>/dev/null; then
    tail -200 $LOG > $LOG.tmp 2>/dev/null && mv $LOG.tmp $LOG 2>/dev/null
  fi
}

# Single instance. Both usual ways of doing it are unusable here, measured on this device:
# the process that launches this script (`setsid sh .../force_dark_watch.sh`) carries the same
# script name at the end of its argv, so any pgrep pattern ending in the script name reports our
# own parent and the new copy exits immediately; and toybox's `flock -n <fd>` answers "Bad file
# descriptor" for an fd that `exec` did open, so the lock silently never works.
#
# The pid file alone was not enough either: two copies launched in the same second both read a
# stale pid, both passed `kill -0`, and both ran (seen on device as two `--serve` processes, each
# with its own `applied` flag, one setting the night mode while the other restored it). `mkdir` is
# atomic on this filesystem, so the directory is the lock; the pid inside it is only there to tell
# a leftover lock from a live one.
LOCK=$D/watch.lock.d

acquire_lock() {
  if mkdir $LOCK 2>/dev/null; then
    echo $$ > $LOCK/pid 2>/dev/null
    return 0
  fi
  old=$(cat $LOCK/pid 2>/dev/null)
  if [ -n "$old" ] && ! kill -0 "$old" 2>/dev/null; then
    rm -rf $LOCK 2>/dev/null
    sleep 1
    if mkdir $LOCK 2>/dev/null; then
      echo $$ > $LOCK/pid 2>/dev/null
      return 0
    fi
  fi
  return 1
}

acquire_lock || exit 0
echo $$ > $PIDF
# Every way out releases the lock; otherwise the next copy waits for a stale one.
trap 'rm -rf $LOCK 2>/dev/null; rm -f $PIDF 2>/dev/null' EXIT INT TERM HUP
log "start pid=$$"

write_state() {
  echo "applied=$1" > $STATE
  echo "pkg=$2" >> $STATE
  echo "night=$3" >> $STATE
  echo "ts=$(date +%s)" >> $STATE
}

front_pkg() {
  # topResumedActivity is what the app itself uses; fall back to window focus on
  # ROMs where the activity dump is unavailable to a shell.
  p=$(dumpsys activity activities 2>/dev/null | grep -m1 'topResumedActivity' | sed -n 's/.*u[0-9]* \([^/]*\)\/.*/\1/p')
  if [ -z "$p" ]; then
    p=$(dumpsys window 2>/dev/null | grep -m1 'mCurrentFocus' | sed -n 's/.*u[0-9]* \([^/]*\)\/.*/\1/p')
  fi
  echo "$p"
}

restore_night() {
  case "$1" in
    already-dark) : ;;
    absent) cmd uimode night no >/dev/null 2>&1 ;;
    0) cmd uimode night auto >/dev/null 2>&1 ;;
    2) cmd uimode night yes >/dev/null 2>&1 ;;
    auto) cmd uimode night auto >/dev/null 2>&1 ;;
    yes) cmd uimode night yes >/dev/null 2>&1 ;;
    no | "") cmd uimode night no >/dev/null 2>&1 ;;
    *) cmd uimode night no >/dev/null 2>&1 ;;
  esac
}

applied=""
last=""
tick=0
emit_state() {
  if [ "$1" != "$last" ] || [ "$tick" -ge 5 ]; then
    write_state $1 "$2" "$3"
    last="$1|$2|$3"
    tick=0
  fi
}
emit_state 0 "" none

while true; do
  tick=$((tick + 1))
  enabled=""
  apps=""
  origin=""
  if [ -f $CONF ]; then
    enabled=$(sed -n 's/^enabled=//p' $CONF 2>/dev/null | head -1)
    apps=$(sed -n 's/^apps=//p' $CONF 2>/dev/null | head -1)
    origin=$(sed -n 's/^origin=//p' $CONF 2>/dev/null | head -1)
  fi

  if [ "$enabled" != "1" ]; then
    if [ "$applied" = "1" ]; then
      restore_night "$origin"
      write_state 0 "" restored
      log "restore on disable origin=$origin"
    else
      write_state 0 "" idle
    fi
    log "exit pid=$$"
    rm -f $PIDF 2>/dev/null
    exit 0
  fi
  fg=$(front_pkg)

  hit=0
  for a in $apps; do
    if [ "$fg" = "$a" ]; then
      hit=1
      break
    fi
  done

  if [ "$hit" = "1" ]; then
    if [ "$applied" != "1" ]; then
      cmd uimode night yes >/dev/null 2>&1
      # Read the mode back. A command that exits 0 is not a screen that changed, and this ROM has
      # answered 0 for switches it then ignored; only a visible dark mode counts as applied, so a
      # refused switch keeps retrying instead of being reported as success.
      if cmd uimode night 2>/dev/null | grep -q 'Night mode: yes'; then
        applied=1
        write_state 1 "$fg" yes
        last="1|$fg|yes"
        tick=0
        log "apply for $fg"
      else
        write_state 0 "$fg" refused
        log "apply for $fg refused by the platform"
        sleep 5
      fi
    else
      emit_state 1 "$fg" yes
    fi
  else
    if [ "$applied" = "1" ]; then
      restore_night "$origin"
      applied=""
      write_state 0 "" restored
      last="0||restored"
      tick=0
      log "restore fg=$fg origin=$origin"
    else
      emit_state 0 "" idle
    fi
  fi

  sleep 1
done
