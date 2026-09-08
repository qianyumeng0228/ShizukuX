scene="com.omarea.vtools"
daemon="scene-daemon"

current_dir=$(dirname $0) # $(pwd)
update_path="$current_dir/$daemon-bak"
origin_path="$current_dir/$daemon"

if [[ ! -e "$origin_path" ]];then
  echo "$origin_path not found !"
  exit 1
fi

dumpsys deviceidle whitelist +$scene 2>&1 >/dev/null
cmd appops set $scene RUN_IN_BACKGROUND allow 2>&1 >/dev/null
cmd appops set $scene RUN_ANY_IN_BACKGROUND allow 2>&1 >/dev/null
am set-standby-bucket $scene active 2>&1 >/dev/null
am set-inactive --user 0 $scene false 2>&1 >/dev/null
am set-bg-restriction-level --user 0 $scene unrestricted 2>&1 >/dev/null
am set-foreground-service-delegate --user 0 com.omarea.vtools start 2>&1 >/dev/null
am unfreeze --sticky com.omarea.vtools 2>&1 >/dev/null

toolkit_dir=$current_dir/toolkit
touch "/cache/USER-NAME" 2> /dev/null
if [[ "$?" == "0" ]] || [[ "$USER" == "ROOT" ]] || [[ "$USER" == "root" ]]; then
  if [[ -f $update_path ]]; then
    rm -f "$origin_path" 2>/dev/null
    killall -9 $daemon 2>/dev/null
    mv -f "$update_path" "$origin_path"
    killall -9 $daemon 2>/dev/null
  fi
  if [[ $(echo "$PATH" | grep 'toolkit') == '' ]] && [[ -d "$toolkit_dir" ]]; then
    export PATH=$PATH:$toolkit_dir
  fi
  if [[ "$(ksud -V 2>/dev/null)" != '' ]]; then
    export KSU=true
    echo 'export KSU=true'
    ksu=/data/adb/ksu/bin
    if [[ $(echo "$PATH" | grep $ksu) == "" ]]; then
      export PATH=$PATH:$ksu
    fi
  fi
  debugfs=$(grep -e "^debugfs" /proc/mounts | head -1 | awk '{print $2}')
  if [[ "$debugfs" == '' ]]; then
    name1=$(cat /dev/urandom | tr -dc 'a-z_' | head -c 8; echo)
    name2=$(cat /dev/urandom | tr -dc 'a-z_' | head -c 8; echo)
    scene_tmp=/dev/$name1
    debugfs="$scene_tmp/$name2"
    if [ -d /data/adb/modules/dimensity_hybrid_governor ]; then
      scene_tmp=/dev/scene
      debugfs="$scene_tmp/debug"
    fi
    mkdir -p "$debugfs"
    mount -t debugfs debugfs "$debugfs" -o mode=755
    chmod 600 "$scene_tmp"
  fi
  if [[ -d /proc/oplus-votable/GAUGE_UPDATE ]]; then
    echo 1000 > /proc/oplus-votable/GAUGE_UPDATE/force_val
    echo 1 > /proc/oplus-votable/GAUGE_UPDATE/force_active
  fi
  if [[ "$1" == "debug" ]]; then
    echo 'Scene-Daemon Starting……'
    $origin_path
  else
    log_path=$current_dir/daemon.log
    if [[ -f $log_path ]]; then
      if [[ -f $current_dir/daemon.stderr.log ]]; then
        rm $current_dir/daemon.stderr.log
      fi
      mv $log_path $current_dir/daemon.stderr.log
    fi
    nohup $origin_path >/dev/null 2>$log_path &
    renice -n -20 $(pidof scene-daemon)
    echo 'Scene-Daemon OK!'
  fi
else
  cache_dir="/data/local/tmp"

  killall -9 $daemon 2>/dev/null

  echo ''
  toolkit=$cache_dir/toolkit
  mkdir -p $toolkit
  # Env PATH add /data/local/tmp
  export PATH=$PATH:$toolkit

  if [[ -f "$current_dir/busybox" ]] && [[ ! -f $toolkit/busybox ]]; then
    echo 'Copy BusyBox'
    cp "$current_dir/busybox" $toolkit/busybox
    chmod 777 $toolkit/busybox

    echo 'Install BusyBox……'
    cd $toolkit
    for applet in `./busybox --list`; do
      case "$applet" in
      "sh"|"busybox"|"shell"|"swapon"|"swapoff"|"mkswap")
        echo '  Skip' > /dev/null
      ;;
      *)
        ./busybox ln -sf busybox "$applet";
      ;;
      esac
    done
    ./busybox ln -sf busybox busybox_1_34_1
  fi

  if [[ -f "$current_dir/binder.so" ]]; then
    cp -f "$current_dir/binder.so" $cache_dir/binder.so
  fi

  target_path="$cache_dir/$daemon"
  echo "Origin File: " $origin_path
  echo "Target File: " $target_path
  echo ''

  cp $origin_path $target_path
  chmod 777 $target_path
  nohup $target_path >/dev/null 2>&1 &
  if [[ $(pgrep scene-daemon) != "" ]]; then
    echo 'Scene-Daemon OK! ^_^'
  else
    echo 'Scene-Daemon Fail! @_@'
  fi

  am start -n $scene/.activities.ActivityStartSplash --es port "$port" -f 0x10008000
  cmd package compile -m speed $scene >/dev/null 2>&1 &
fi

# kill old daemon
kill_old_daemon(){
  res=$(ss -tulnp | grep ":8765")
  if [ ! -z "$res" ]; then
      pid=$(echo "$res" | sed -E 's/.*pid=([0-9]+),.*/\1/')
      if [[ "$(cat /proc/$pid/cmdline)" == *scene-daemon* ]]; then
        kill $pid
      fi
  fi
}
