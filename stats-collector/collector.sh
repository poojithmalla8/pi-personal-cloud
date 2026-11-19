#!/bin/bash
# Host stats collector for a personal Nextcloud on a Raspberry Pi.
# Run from the Pi user's crontab every minute (no sudo). The user must be in the docker group.
# Writes stats.json into the Nextcloud data directory via `docker exec`.
#
# Optional environment variables:
#   APP_CONTAINER   Nextcloud container name (default: nextcloud_app)
#   DB_CONTAINER    MariaDB container name   (default: nextcloud_db)
#   NC_DATADIR      data directory *inside* the app container
#                   (default: /var/www/html/data — matches docker/docker-compose.yml)
#   IFACES          network interfaces to sum (default: "eth0 wlan0 tailscale0")
#   DISKS           block devices for the power estimate (default: "sda sdb")
#
# Example crontab line (see also crontab.example):
#   * * * * * $HOME/cloud-stats/collector.sh >>$HOME/cloud-stats/collector.log 2>&1
set -u
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
APP=${APP_CONTAINER:-nextcloud_app}
DB=${DB_CONTAINER:-nextcloud_db}
DATADIR=${NC_DATADIR:-/var/www/html/data}       # path INSIDE the app container
IFACES=${IFACES:-"eth0 wlan0 tailscale0"}
DISKS=${DISKS:-"sda sdb"}
DIR="$HOME/cloud-stats"; STATE="$DIR/state"
mkdir -p "$DIR"
exec 9>"$DIR/.lock"; flock -n 9 || exit 0

# ---- load state ----
last_ts=0; boot_id=""; day=""; month=""; cpu_total=0; cpu_idle=0
day_rx=0; day_tx=0; mon_rx=0; mon_tx=0; day_wh=0; mon_wh=0; heavy_ts=0
html_b=-1; db_b=-1; appdata_b=-1; preview_b=-1; trash_b=-1; versions_b=-1; images_b=-1; images_json="[]"
for i in $IFACES; do eval "last_rx_$i=0; last_tx_$i=0"; done
for d in $DISKS; do eval "last_io_$d=0"; done
[ -f "$STATE" ] && . "$STATE"

now=$(date +%s); today=$(date +%F); thismonth=$(date +%Y-%m)
cur_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
rebooted=0; [ "$cur_boot" != "$boot_id" ] && rebooted=1
[ "$today" != "$day" ] && { day_rx=0; day_tx=0; day_wh=0; day=$today; }
[ "$thismonth" != "$month" ] && { mon_rx=0; mon_tx=0; mon_wh=0; month=$thismonth; }

# ---- network ----
boot_rx=0; boot_tx=0; ifjson=""
for i in $IFACES; do
  f=/sys/class/net/$i/statistics
  [ -r $f/rx_bytes ] || continue
  rx=$(cat $f/rx_bytes); tx=$(cat $f/tx_bytes)
  eval "lrx=\$last_rx_$i; ltx=\$last_tx_$i"
  if [ $rebooted = 1 ] || [ "$rx" -lt "$lrx" ] || [ "$tx" -lt "$ltx" ]; then drx=$rx; dtx=$tx  # counter reset
  else drx=$((rx-lrx)); dtx=$((tx-ltx)); fi
  [ "$last_ts" = 0 ] && { drx=0; dtx=0; }   # first run: just take a baseline
  day_rx=$((day_rx+drx)); day_tx=$((day_tx+dtx)); mon_rx=$((mon_rx+drx)); mon_tx=$((mon_tx+dtx))
  boot_rx=$((boot_rx+rx)); boot_tx=$((boot_tx+tx))
  eval "last_rx_$i=$rx; last_tx_$i=$tx"
  ifjson="$ifjson${ifjson:+,}{\"name\":\"$i\",\"rx\":$rx,\"tx\":$tx}"
done

# ---- cpu ----
read -r _ u n s idle iow irq sirq st _ < /proc/stat
tot=$((u+n+s+idle+iow+irq+sirq+st)); idl=$((idle+iow))
dt=$((tot-cpu_total)); di=$((idl-cpu_idle))
if [ $rebooted = 0 ] && [ $dt -gt 0 ]; then cpu_pct=$(( (100*(dt-di))/dt )); else cpu_pct=0; fi
cpu_total=$tot; cpu_idle=$idl
read -r load1 load5 load15 _ < /proc/loadavg
temp_c=$(awk '{printf "%.1f",$1/1000}' /sys/class/thermal/thermal_zone0/temp 2>/dev/null || echo null)
# throttling (vcgencmd works without sudo if user is in the "video" group)
thr_raw=null; thr_now=false; thr_uv=false; thr_th=false; thr_soft=false; thr_cap=false
if command -v vcgencmd >/dev/null 2>&1; then
  t=$(vcgencmd get_throttled 2>/dev/null | sed -n 's/.*throttled=\(0x[0-9a-fA-F]*\).*/\1/p')
  if [ -n "$t" ]; then
    v=$((t)); thr_raw="\"$t\""
    [ $((v & 1)) -ne 0 ] && thr_uv=true; [ $((v & 2)) -ne 0 ] && thr_cap=true
    [ $((v & 4)) -ne 0 ] && thr_th=true; [ $((v & 8)) -ne 0 ] && thr_soft=true
    [ $((v & 15)) -ne 0 ] && thr_now=true
  fi
fi
uptime_s=$(awk '{printf "%d",$1}' /proc/uptime)

# ---- disks + power estimate (ESTIMATE ONLY) ----
diskjson=""; disk_w=0; ndisk=0
for d in $DISKS; do
  [ -r /sys/block/$d/stat ] || continue
  io=$(awk '{print $3+$7}' /sys/block/$d/stat)   # sectors read + written
  eval "lio=\$last_io_$d"
  active=false; [ "$io" != "$lio" ] && active=true
  eval "last_io_$d=$io"; ndisk=$((ndisk+1))
  diskjson="$diskjson${diskjson:+,}{\"name\":\"$d\",\"io_active\":$active}"
done
# Model: 3.0 W base + 3.0 W * cpu util + 2.5 W per HDD (assumed spinning; spin-down can't be read without root)
watts=$(awk -v c=$cpu_pct -v n=$ndisk 'BEGIN{printf "%.2f", 3.0 + 3.0*c/100 + 2.5*n}')
el=$((now-last_ts)); { [ "$last_ts" = 0 ] || [ $el -gt 600 ] || [ $el -lt 0 ]; } && el=0  # gaps (Pi off) not counted
wh=$(awk -v w=$watts -v s=$el 'BEGIN{printf "%.4f", w*s/3600}')
day_wh=$(awk -v a=$day_wh -v b=$wh 'BEGIN{printf "%.4f",a+b}'); mon_wh=$(awk -v a=$mon_wh -v b=$wh 'BEGIN{printf "%.4f",a+b}')

# ---- hourly: Nextcloud disk usage ----
if [ $((now-heavy_ts)) -ge 3600 ]; then
  num() { case "$1" in ''|*[!0-9]*) echo -1;; *) echo "$1";; esac; }
  html_b=$(num "$(docker exec "$APP" du -sb /var/www/html 2>/dev/null | awk '{print $1}')")
  dx() { docker exec -u www-data "$APP" sh -c "$1" 2>/dev/null; }
  appdata_b=$(num "$(dx "du -scb $DATADIR/appdata_* 2>/dev/null | tail -1 | cut -f1")")
  preview_b=$(num "$(dx "du -scb $DATADIR/appdata_*/preview 2>/dev/null | tail -1 | cut -f1")")
  trash_b=$(num "$(dx "du -scb $DATADIR/*/files_trashbin 2>/dev/null | tail -1 | cut -f1")")
  versions_b=$(num "$(dx "du -scb $DATADIR/*/files_versions 2>/dev/null | tail -1 | cut -f1")")
  db_b=$(num "$(docker exec "$DB" sh -c 'P="${MARIADB_ROOT_PASSWORD:-${MYSQL_ROOT_PASSWORD:-}}"; S="${MARIADB_DATABASE:-${MYSQL_DATABASE:-nextcloud}}"; C=$(command -v mariadb || command -v mysql); "$C" -uroot -p"$P" -N -B -e "SELECT COALESCE(SUM(data_length+index_length),0) FROM information_schema.tables WHERE table_schema=\"$S\""' 2>/dev/null | tr -d '[:space:]')")
  images_b=0; images_json=""
  while read -r name size; do
    [ -z "$name" ] && continue
    images_b=$((images_b+size)); images_json="$images_json${images_json:+,}{\"name\":\"$name\",\"bytes\":$size}"
  done < <(docker image inspect $(docker image ls -q 2>/dev/null | sort -u) --format '{{index .RepoTags 0}} {{.Size}}' 2>/dev/null)
  images_json="[$images_json]"
  heavy_ts=$now
fi

# ---- write JSON into container ----
json=$(cat <<J
{"updated":$now,"host":"$(hostname)","uptime_s":$uptime_s,
"cpu":{"util_pct":$cpu_pct,"load1":$load1,"load5":$load5,"load15":$load15,"temp_c":$temp_c},
"throttle":{"raw":$thr_raw,"active":$thr_now,"under_voltage":$thr_uv,"freq_capped":$thr_cap,"throttled":$thr_th,"soft_temp_limit":$thr_soft},
"network":{"today":{"date":"$day","rx":$day_rx,"tx":$day_tx},"month":{"month":"$month","rx":$mon_rx,"tx":$mon_tx},"since_boot":{"rx":$boot_rx,"tx":$boot_tx},"interfaces":[$ifjson]},
"power":{"estimated":true,"model":"3.0W base + 3.0W x CPU util + 2.5W per HDD","watts":$watts,"today_kwh":$(awk -v a=$day_wh 'BEGIN{printf "%.4f",a/1000}'),"month_kwh":$(awk -v a=$mon_wh 'BEGIN{printf "%.4f",a/1000}'),"disks":[$diskjson]},
"nextcloud_disk":{"updated":$heavy_ts,"app_code":$html_b,"database":$db_b,"appdata":$appdata_b,"previews":$preview_b,"trash":$trash_b,"versions":$versions_b,"docker_images":$images_b,"images":$images_json}}
J
)
if printf '%s\n' "$json" | docker exec -i -u www-data "$APP" sh -c "cat > $DATADIR/stats.json.tmp && mv $DATADIR/stats.json.tmp $DATADIR/stats.json"; then :; else echo "$(date) failed to write stats into $APP" >&2; fi
printf '%s\n' "$json" > "$DIR/stats.json"

# ---- save state ----
{
  echo "last_ts=$now boot_id=$cur_boot day=$day month=$month cpu_total=$cpu_total cpu_idle=$cpu_idle"
  echo "day_rx=$day_rx day_tx=$day_tx mon_rx=$mon_rx mon_tx=$mon_tx day_wh=$day_wh mon_wh=$mon_wh heavy_ts=$heavy_ts"
  echo "html_b=$html_b db_b=$db_b appdata_b=$appdata_b preview_b=$preview_b trash_b=$trash_b versions_b=$versions_b images_b=$images_b"
  printf "images_json=%q\n" "$images_json"
  for i in $IFACES; do eval "echo last_rx_$i=\$last_rx_$i last_tx_$i=\$last_tx_$i"; done
  for d in $DISKS; do eval "echo last_io_$d=\$last_io_$d"; done
} > "$STATE.tmp" && mv "$STATE.tmp" "$STATE"
