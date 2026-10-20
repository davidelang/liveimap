#!/bin/sh
# Restore one lab datastore from /liveimap-seed, or seed GreenMail after IMAP accepts.
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
    greenmail|greenmail-16|greenmail-214|greenmail-dev)
      seed_greenmail ;;
    *)
      bad ;;
  esac
}

# GreenMail has no tar. Seed localhost only after port 3143 accepts.
seed_greenmail() {
  rm -f /tmp/liveimap-greenmail-ready
  seed_greenmail_run
  touch /tmp/liveimap-greenmail-ready
}

seed_greenmail_run() {
  if ! command -v bash >/dev/null 2>&1; then
    echo "greenmail seed needs bash" >&2
    exit 1
  fi
  bash <<'BASH'
set -eu
exec 3<>/dev/tcp/127.0.0.1/3143
IFS= read -r _greeting <&3 || { echo "greenmail greeting missing" >&2; exit 1; }
tag_n=1
imap_send() { printf '%s\r\n' "$1" >&3; }
imap_until() {
  want=$1
  : > /tmp/liveimap-untagged
  while IFS= read -r line <&3; do
    line=${line%$'\r'}
    case $line in
      "$want "*) printf '%s\n' "$line"; return 0 ;;
      "+ "*) printf '%s\n' "$line"; return 0 ;;
      "+"*) printf '%s\n' "$line"; return 0 ;;
      \**) printf '%s\n' "$line" >> /tmp/liveimap-untagged ;;
    esac
  done
  echo "greenmail imap stalled" >&2
  return 1
}
mutf7() {
  rest=$1
  out=
  buf=
  flush_buf() {
    [ -n "$buf" ] || return 0
    enc=$(printf '%s' "$buf" | iconv -f UTF-8 -t UTF-16BE | base64 | tr -d '\n=')
    enc=$(printf '%s' "$enc" | tr '/' ',')
    out="${out}&${enc}-"
    buf=
  }
  while [ -n "$rest" ]; do
    ch=${rest:0:1}
    rest=${rest:1}
    cp=$(printf '%d' "'$ch")
    if [ "$cp" -ge 32 ] && [ "$cp" -le 126 ]; then
      flush_buf
      if [ "$ch" = "&" ]; then out="${out}&-"; else out="${out}${ch}"; fi
    else
      buf="${buf}${ch}"
    fi
  done
  flush_buf
  printf '%s' "$out"
}
qname() { printf '"%s"' "$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g')"; }
run_tag() {
  tag="a$tag_n"
  tag_n=$((tag_n + 1))
  imap_send "$tag $1"
  imap_until "$tag"
}

login_user() {
  user=$1
  pass=$2
  tag="a$tag_n"
  tag_n=$((tag_n + 1))
  plen=$(printf '%s' "$pass" | wc -c | tr -d ' ')
  imap_send "$tag LOGIN $(qname "$user") {$plen}"
  imap_until "+" >/dev/null
  printf '%s\r\n' "$pass" >&3
  line=$(imap_until "$tag")
  case $line in
    *" OK "*) return 0 ;;
  esac
  echo "greenmail login failed" >&2
  return 1
}

pass_for() {
  awk -F '\t' -v u="$1" 'NR > 1 && $1 == u { print $2; exit }' /liveimap-check/accounts.tsv
}

create_mailbox() {
  name=$1
  run_tag "CREATE $(qname "$name")" >/dev/null || true
}

append_file() {
  mbox=$1
  flags=$2
  path=$3
  size=$(wc -c < "$path" | tr -d ' ')
  tag="a$tag_n"
  tag_n=$((tag_n + 1))
  if [ "$flags" = "-" ]; then
    imap_send "$tag APPEND $(qname "$mbox") {$size}"
  else
    imap_send "$tag APPEND $(qname "$mbox") ($flags) {$size}"
  fi
  imap_until "+" >/dev/null
  cat "$path" >&3
  printf '\r\n' >&3
  line=$(imap_until "$tag")
  case $line in
    *" OK "*) return 0 ;;
  esac
  echo "greenmail append failed" >&2
  return 1
}

slug() { printf '%s' "$1" | tr '/' '_'; }

while IFS=$(printf '\t') read -r user pass profile; do
  [ "$user" = "user" ] && continue
  [ "$profile" = "base" ] || continue
  [ -n "$pass" ] || continue
  login_user "$user" "$pass"
  run_tag 'LIST "" "INBOX"' >/dev/null
  delim=
  while IFS= read -r line; do
    case $line in
      "* LIST "*) ;;
      *) continue ;;
    esac
    rest=${line#*\"}
    one=${rest%%\"*}
    if [ "${#one}" -eq 1 ]; then
      delim=$one
      break
    fi
  done < /tmp/liveimap-untagged
  [ -n "$delim" ] || delim=/
  while IFS=$(printf '\t') read -r fuser folder count _special _sub; do
    [ "$fuser" = "$user" ] || continue
    [ "$folder" = "INBOX" ] && continue
    parts=
    rest=$folder
    while [ -n "$rest" ]; do
      seg=${rest%%/*}
      if [ -z "$parts" ]; then parts=$seg; else parts="$parts/$seg"; fi
      enc=$(mutf7 "$parts")
      enc=$(printf '%s' "$enc" | tr '/' "$delim")
      create_mailbox "$enc"
      case $rest in
        */*) rest=${rest#*/} ;;
        *) rest= ;;
      esac
    done
  done < /liveimap-check/folders.tsv
  while IFS=$(printf '\t') read -r muser mfolder file flags _date _sum; do
    [ "$muser" = "$user" ] || continue
    if [ "$mfolder" = "INBOX" ]; then
      mbox=INBOX
    else
      enc=$(mutf7 "$mfolder")
      mbox=$(printf '%s' "$enc" | tr '/' "$delim")
    fi
    append_file "$mbox" "$flags" "/liveimap-corpus/$user/$(slug "$mfolder")/$file"
  done < /liveimap-check/manifest.tsv
  run_tag LOGOUT >/dev/null || true
  exec 3<&-
  exec 3<>/dev/tcp/127.0.0.1/3143
  IFS= read -r _greeting <&3 || true
done < /liveimap-check/accounts.tsv
exec 3<&- || true
BASH
}

wait_imap() {
  i=0
  while [ "$i" -lt 180 ]; do
    if bash -c 'echo >/dev/tcp/127.0.0.1/3143' >/dev/null 2>&1; then
      return 0
    fi
    i=$((i + 1))
    sleep 1
  done
  echo "greenmail imap did not open" >&2
  return 1
}

run_restore_unless_skipped() {
  if skip_restore; then
    return 0
  fi
  case "$SERVER" in
    greenmail|greenmail-16|greenmail-214|greenmail-dev) ;;
    *) check_stamp ;;
  esac
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

if [ "${1:-}" = "--greenmail" ]; then
  shift
  if [ "${1:-}" = "--" ]; then
    shift
  fi
  if [ "$#" -lt 1 ]; then
    echo "greenmail command missing" >&2
    exit 1
  fi
  rm -f /tmp/liveimap-greenmail-ready
  "$@" &
  pid=$!
  if ! wait_imap; then
    kill "$pid" 2>/dev/null || true
    wait "$pid" 2>/dev/null || true
    exit 1
  fi
  if ! skip_restore; then
    if ! seed_greenmail_run; then
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
      exit 1
    fi
  fi
  touch /tmp/liveimap-greenmail-ready
  wait "$pid"
  exit $?
fi

run_restore_unless_skipped
