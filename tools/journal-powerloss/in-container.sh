#!/bin/bash
# One run inside the container. Args: MODE (sync|nosync) ACKS. Env: CP (classpath, container paths), JAVA_FLAGS.
# Journal lives on a LazyFS mount; after ACKS acknowledgements: SIGKILL the child, drop LazyFS's un-synced cache (= power cut), remount
# (empty kernel cache), verify through the mount.
MODE=$1; ACKS=$2
LZ=/opt/lazyfs-src/lazyfs/build/lazyfs
ROOT=/work/root; MNT=/work/mnt; FIFO=/work/faults.fifo; LOG=/work/lazyfs.log; OUT=/work/child.out
mkdir -p $ROOT $MNT
cat > /work/lazyfs.toml <<TOML
[faults]
fifo_path="$FIFO"
[cache]
apply_eviction=false
[cache.simple]
custom_size="1gb"
blocks_per_page=1
[filesystem]
log_all_operations=false
logfile="$LOG"
TOML
# FUSEOPTS: extra FUSE mount options (the plain mount is what LazyFS documents; see the README of this directory's findings in the report)
mount_lz() { $LZ $MNT --config-path /work/lazyfs.toml -o allow_other -o modules=subdir -o subdir=$ROOT $FUSEOPTS -s > /work/lazyfs.stdout 2>&1 &
  for i in $(seq 50); do mountpoint -q $MNT && return 0; sleep 0.2; done; echo "LazyFS did not mount"; cat /work/lazyfs.stdout; exit 2; }
mount_lz
echo "mounted: $(mount | grep $MNT)"
if [ "$MODE" = sync ]; then MAIN=crowdvsmarket.server.journal.JournalKillMain; else MAIN=crowdvsmarket.server.journal.JournalNoSyncMain; fi
java $JAVA_FLAGS -Dchronicle.analytics.disable=true -cp "$CP" $MAIN $MNT/journal 0 > $OUT 2> /work/child.err &
CHILD=$!
for i in $(seq 600); do
  [ "$(grep -c '^ACK ' $OUT)" -ge "$ACKS" ] && break
  kill -0 $CHILD 2>/dev/null || { echo "child died early"; cat /work/child.err | head -30; exit 3; }
  sleep 0.1
done
kill -9 $CHILD; wait $CHILD 2>/dev/null
# complete ACK lines only; every one of them was printed after its append (and sync) returned, before the kill
LAST=$(grep -E '^ACK [0-9]+ [0-9]+ [0-9]+$' $OUT | tail -1); NACK=$(grep -cE '^ACK [0-9]+ [0-9]+ [0-9]+$' $OUT)
echo "child killed (SIGKILL); ACKs=$NACK last='$LAST'"
echo "lazyfs::unsynced-data-report" > $FIFO; sleep 1
echo "lazyfs::clear-cache" > $FIFO; sleep 2
grep -i "clear\|cache" $LOG | tail -3
# power is back: new mount, nothing of the old kernel page cache
umount $MNT || fusermount3 -u $MNT; sleep 1; pkill -x lazyfs; sleep 1
mount_lz
set -- $LAST
echo "files under the root after the cut:"; ls -l $ROOT/journal 2>&1 | head
java $JAVA_FLAGS -Dchronicle.analytics.disable=true -cp "$CP" crowdvsmarket.server.journal.JournalPowerLossVerifier $MNT/journal $2 $3
RC=$?
echo "VERIFIER_RC=$RC MODE=$MODE ACKS=$NACK"
umount $MNT 2>/dev/null
exit $RC
