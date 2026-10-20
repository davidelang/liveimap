#!/bin/sh
# Restore one lab datastore from /liveimap-seed.
# Installed as /liveimap-reseed. Does not call an admin API, reconstruct, or ctl_mboxlist.
set -eu

SERVER=${LIVEIMAP_SERVER:-}
if [ -z "$SERVER" ]; then
  echo "LIVEIMAP_SERVER is unset" >&2
  exit 1
fi

bad() {
  echo "tools/test-servers/seed-snapshot.sh $SERVER" >&2
  exit 1
}

skip_restore() {
  [ -f /.liveimap-no-reseed ] || return 1
  val=$(tr -d ' \t\r\n' < /.liveimap-no-reseed)
  [ "$val" = "no" ]
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk 'NR==1 { print $1; exit }'
    return 0
  fi
  if command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "$1" | awk '{ print $NF; exit }'
    return 0
  fi
  bad
}

stamp_hash() {
  key=$1
  awk -v key="$key" '$1 == key { print $2; exit }' /liveimap-seed/seed-snapshot.stamp
}

check_stamp() {
  tarf=/liveimap-seed/seed-snapshot.tar
  stamp=/liveimap-seed/seed-snapshot.stamp
  if [ ! -f "$tarf" ] || [ ! -s "$stamp" ]; then
    bad
  fi
  want=$(awk '$1 == "image" { print $2; exit }' "$stamp")
  if [ -z "${LIVEIMAP_IMAGE_ID:-}" ] || [ "$want" != "$LIVEIMAP_IMAGE_ID" ]; then
    bad
  fi
  check_one() {
    key=$1
    path=$2
    got=$(sha256_file "$path")
    have=$(stamp_hash "$key")
    if [ -z "$have" ] || [ "$got" != "$have" ]; then
      bad
    fi
  }
  check_one tools/test-servers/generated/corpus-v1/manifest.tsv /liveimap-check/manifest.tsv
  check_one tools/test-servers/seed/accounts.tsv /liveimap-check/accounts.tsv
  check_one tools/test-servers/seed/folders.tsv /liveimap-check/folders.tsv
  check_one tools/test-servers/seed/corpus-v1.sums /liveimap-check/corpus-v1.sums
}

wipe_dir() {
  p=$1
  if [ -d "$p" ]; then
    find "$p" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
  elif [ -e "$p" ]; then
    rm -rf "$p"
    mkdir -p "$p"
  else
    mkdir -p "$p"
  fi
}

chown_paths() {
  owner=$1
  shift
  chown -R "$owner" "$@"
}

base_users() {
  awk -F '\t' 'NR > 1 && $3 == "base" { print $1 }' /liveimap-check/accounts.tsv
}

chown_courier_606() {
  uid=2500
  base_users > /tmp/liveimap-users
  while read -r user; do
    [ -n "$user" ] || continue
    if [ -d "/var/mail/$user" ]; then
      gid=$uid
      if id "$user" >/dev/null 2>&1; then
        gid=$(id -g "$user")
      fi
      chown -R "$uid:$gid" "/var/mail/$user"
    fi
    uid=$((uid + 1))
  done < /tmp/liveimap-users
  rm -f /tmp/liveimap-users
}

chown_uwimap() {
  base_users > /tmp/liveimap-users
  while read -r user; do
    [ -n "$user" ] || continue
    if ! id "$user" >/dev/null 2>&1; then
      continue
    fi
    u=$(id -u "$user")
    g=$(id -g "$user")
    if [ -e "/var/mail/$user" ] && [ "/var/mail/$user" != "/var/mail" ]; then
      chown "$u:$g" "/var/mail/$user"
    fi
    if [ -d "/home/$user" ] && [ "/home/$user" != "/home" ]; then
      chown -R "$u:$g" "/home/$user"
    fi
  done < /tmp/liveimap-users
  rm -f /tmp/liveimap-users
}

restore_tar() {
  paths=$1
  owner=$2
  # shellcheck disable=SC2086
  for p in $paths; do
    wipe_dir "$p"
  done
  if [ "$SERVER" = "zimbra-883" ]; then
    if tar -tf /liveimap-seed/seed-snapshot.tar | grep -E '(^|/)opt/zimbra/bin(/|$)' >/dev/null 2>&1; then
      bad
    fi
  fi
  tar --no-overwrite-dir -C / -xf /liveimap-seed/seed-snapshot.tar
  case "$owner" in
    courier-606) chown_courier_606 ;;
    uwimap) chown_uwimap ;;
    "") ;;
    *)
      # shellcheck disable=SC2086
      chown_paths "$owner" $paths
      ;;
  esac
  touch /tmp/liveimap-restored
}

do_restore() {
  case "$SERVER" in
    cyrus-22|cyrus-25)
      restore_tar "/var/spool/cyrus/mail /var/lib/cyrus /var/spool/sieve" "102:8" ;;
    cyrus-24|cyrus-36)
      restore_tar "/var/spool/cyrus/mail /var/lib/cyrus /var/spool/sieve" "101:8" ;;
    cyrus-3124|cyrus-3137)
      restore_tar "/var/spool/cyrus/mail /var/lib/cyrus /var/spool/cyrus/sieve" "999:999" ;;
    dovecot-10|dovecot-12|dovecot-22|dovecot-245|dovecot-dev)
      restore_tar "/srv/vmail" "1000:1000" ;;
    courier-50)
      restore_tar "/var/vmail" "1000:1000" ;;
    courier-606)
      restore_tar "/var/mail" "courier-606" ;;
    uwimap)
      restore_tar "/var/mail /home" "uwimap" ;;
    stalwart-0155)
      restore_tar "/opt/stalwart/data" "0:0" ;;
    stalwart|stalwart-01625|stalwart-dev)
      restore_tar "/var/lib/stalwart" "2000:2000" ;;
    zimbra-883)
      restore_tar "/opt/zimbra/store /opt/zimbra/index /opt/zimbra/db/data /opt/zimbra/data/ldap/mdb/db /opt/zimbra/redolog" "1000:1000" ;;
    *)
      bad ;;
  esac
}

run_restore_unless_skipped() {
  if skip_restore; then
    return 0
  fi
  check_stamp
  do_restore
}

if [ "${1:-}" = "--before" ]; then
  shift
  if [ "${1:-}" = "--" ]; then
    shift
  fi
  run_restore_unless_skipped
  exec "$@"
fi

run_restore_unless_skipped
