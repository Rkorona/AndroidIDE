package com.termux.app;

import com.termux.shared.logger.Logger;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Patches an ELF64 binary (the bash bootstrap) so that Android 16's dynamic
 * linker automatically loads libandroidide-exec-wrapper.so as a hard dependency
 * whenever linker64 maps bash into memory.
 *
 * <p>Android 16 introduced a tightened SELinux policy that blocks execve() on
 * files in the app's private data directory (app_data_file domain).  The only
 * reliable way to make bash's child-process exec calls succeed is to have an
 * execve()-intercepting library loaded <em>inside bash's address space</em>.
 *
 * <p>LD_PRELOAD is ignored by linker64 when it is invoked in standalone /
 * direct-invocation mode (i.e. {@code execv(linker64, [linker64, bash, ...])}).
 * The only way that does work is a DT_NEEDED entry baked into the binary itself.
 *
 * <h3>What the patcher does</h3>
 * <ol>
 *   <li>Adds {@code DT_RUNPATH = "$ORIGIN/../lib"} so linker64 searches the
 *       termux prefix lib directory when resolving DT_NEEDED entries.</li>
 *   <li>Inserts {@code DT_NEEDED = "libandroidide-exec-wrapper.so"} <em>before</em>
 *       all existing DT_NEEDED entries.  Position matters: bionic resolves PLT
 *       symbols in DT_NEEDED load order, so exec-wrapper must be first to win
 *       over libc's execve() definition.</li>
 *   <li>Appends the two new strings to the binary's dynamic string table
 *       (DT_STRTAB / .dynstr) by overwriting zero-filled gap bytes that follow
 *       the existing string content within the same PT_LOAD segment.</li>
 * </ol>
 *
 * <p>All three changes are in-place (no file-size change).  If there is not
 * enough space the method returns {@code false} and the binary is left intact.
 */
public final class ElfPatcher {

    private static final String TAG = "ElfPatcher";

    // ELF identification
    private static final byte ELFCLASS64 = 2;

    // Program-header types
    private static final int PT_LOAD    = 1;
    private static final int PT_DYNAMIC = 2;

    // Dynamic-section tag values
    private static final long DT_NULL    = 0L;
    private static final long DT_NEEDED  = 1L;
    private static final long DT_STRTAB  = 5L;
    private static final long DT_STRSZ   = 10L;
    private static final long DT_RUNPATH = 29L;

    /** Library name written as DT_NEEDED. */
    static final String EXEC_WRAPPER_LIB = "libandroidide-exec-wrapper.so";

    /** DT_RUNPATH value written into bash so the linker finds EXEC_WRAPPER_LIB. */
    private static final String RUNPATH = "$ORIGIN/../lib";

    private ElfPatcher() {}

    /**
     * Patches {@code file} in-place.
     *
     * @return {@code true}  if the patch was applied or was already present,
     *         {@code false} if patching was skipped (insufficient space, wrong
     *         ELF format, or any I/O error).
     */
    public static boolean patchBash(File file) {
        if (!file.exists()) {
            Logger.logWarn(TAG, "patchBash: file does not exist: " + file);
            return false;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw");
             FileChannel ch = raf.getChannel()) {

            long fileSize = ch.size();
            if (fileSize < 64) return false; // too small to be a valid ELF

            MappedByteBuffer buf = ch.map(FileChannel.MapMode.READ_WRITE, 0, fileSize);
            buf.order(ByteOrder.LITTLE_ENDIAN);

            // ── 1. Validate ELF magic and class ─────────────────────────────────
            if (buf.get(0) != 0x7f || buf.get(1) != 'E' ||
                buf.get(2) != 'L'  || buf.get(3) != 'F') {
                Logger.logWarn(TAG, "patchBash: not an ELF file: " + file);
                return false;
            }
            if (buf.get(4) != ELFCLASS64) {
                Logger.logWarn(TAG, "patchBash: not ELF64: " + file);
                return false;
            }

            // ── 2. Read ELF64 header ─────────────────────────────────────────────
            // e_phoff    @ 32 (8 bytes)
            // e_phentsize@ 54 (2 bytes)
            // e_phnum    @ 56 (2 bytes)
            long phoff     = buf.getLong(32);
            int  phEntSize = buf.getShort(54) & 0xFFFF;
            int  phNum     = buf.getShort(56) & 0xFFFF;

            if (phEntSize < 56 || phNum == 0) return false;

            // ── 3. Locate PT_DYNAMIC and PT_LOAD segments ────────────────────────
            long dynFileOffset = -1, dynFileSize = -1;
            // Up to 8 PT_LOAD segments (plenty for any real binary)
            long[] loadOff   = new long[8];
            long[] loadVaddr = new long[8];
            long[] loadFsz   = new long[8];
            int    loadCnt   = 0;

            for (int i = 0; i < phNum; i++) {
                int phBase = (int)(phoff + (long)i * phEntSize);
                if (phBase + 56 > fileSize) break;

                int pType = buf.getInt(phBase); // p_type (4 bytes @ 0)
                if (pType == PT_DYNAMIC) {
                    dynFileOffset = buf.getLong(phBase + 8);  // p_offset
                    dynFileSize   = buf.getLong(phBase + 32); // p_filesz
                } else if (pType == PT_LOAD && loadCnt < loadOff.length) {
                    loadOff  [loadCnt] = buf.getLong(phBase + 8);  // p_offset
                    loadVaddr[loadCnt] = buf.getLong(phBase + 16); // p_vaddr
                    loadFsz  [loadCnt] = buf.getLong(phBase + 32); // p_filesz
                    loadCnt++;
                }
            }
            if (dynFileOffset < 0 || dynFileSize < 16) {
                Logger.logWarn(TAG, "patchBash: no PT_DYNAMIC: " + file);
                return false;
            }

            // ── 4. Parse dynamic section ─────────────────────────────────────────
            int dynEntCount = (int)(dynFileSize / 16);
            long strtabVaddr  = -1, strtabSz    = -1;
            long strtabSzOff  = -1;   // file offset of DT_STRSZ's d_val field
            int  firstNeeded  = -1;   // index of first DT_NEEDED entry
            int  firstNull    = -1;   // index of first DT_NULL (terminator)
            int  nullCount    = 0;    // total DT_NULL entries (including padding)

            for (int i = 0; i < dynEntCount; i++) {
                long entBase = dynFileOffset + (long)i * 16;
                if (entBase + 16 > fileSize) break;
                long dTag = buf.getLong((int)entBase);
                long dVal = buf.getLong((int)(entBase + 8));

                if      (dTag == DT_STRTAB) { strtabVaddr = dVal; }
                else if (dTag == DT_STRSZ)  { strtabSz = dVal; strtabSzOff = entBase + 8; }
                else if (dTag == DT_NEEDED && firstNeeded < 0) { firstNeeded = i; }

                if (dTag == DT_NULL) {
                    if (firstNull < 0) firstNull = i;
                    nullCount++;
                }
            }

            if (strtabVaddr < 0 || strtabSz < 0 || firstNull < 0) {
                Logger.logWarn(TAG, "patchBash: incomplete dynamic section: " + file);
                return false;
            }

            // ── 5. Convert DT_STRTAB virtual address to file offset ──────────────
            long strtabFileOff = -1;
            for (int i = 0; i < loadCnt; i++) {
                if (strtabVaddr >= loadVaddr[i] &&
                    strtabVaddr <  loadVaddr[i] + loadFsz[i]) {
                    strtabFileOff = strtabVaddr - loadVaddr[i] + loadOff[i];
                    // Also find end of this PT_LOAD in file (upper bound for free space)
                    break;
                }
            }
            if (strtabFileOff < 0) {
                Logger.logWarn(TAG, "patchBash: cannot map DT_STRTAB vaddr to file: " + file);
                return false;
            }

            // ── 6. Check if already patched ──────────────────────────────────────
            // Scan strtab content for EXEC_WRAPPER_LIB name
            byte[] wrapperNameBytes = (EXEC_WRAPPER_LIB + "\0").getBytes("UTF-8");
            byte[] runpathBytes     = (RUNPATH         + "\0").getBytes("UTF-8");
            long   strtabEnd        = strtabFileOff + strtabSz;

            if (containsBytes(buf, strtabFileOff, (int)strtabSz + wrapperNameBytes.length,
                              fileSize, wrapperNameBytes)) {
                Logger.logInfo(TAG, "patchBash: already patched — " + file);
                return true;
            }

            // ── 7. Check we have enough DT_NULL padding slots ────────────────────
            // We need to insert 2 entries (DT_RUNPATH + DT_NEEDED) before firstNeeded.
            // That consumes 2 padding slots; 1 must remain as the DT_NULL terminator.
            if (nullCount < 3) {
                Logger.logWarn(TAG, "patchBash: only " + nullCount +
                    " DT_NULL entries — need ≥3 for patching: " + file);
                return false;
            }

            // ── 8. Find free bytes after strtab for the new strings ──────────────
            long spaceNeeded = runpathBytes.length + wrapperNameBytes.length; // ≈ 45 bytes

            // The strtab must be within a PT_LOAD segment.  Find how many zero bytes
            // exist immediately after the strtab content within the mapped region.
            long freeStart = strtabEnd;
            long freeEnd   = freeStart;
            // Limit scan to at most 512 bytes to avoid running into real section data
            long scanLimit = Math.min(freeStart + 512, fileSize);
            while (freeEnd < scanLimit && buf.get((int)freeEnd) == 0) {
                freeEnd++;
            }
            long freeBytes = freeEnd - freeStart;

            if (freeBytes < spaceNeeded) {
                Logger.logWarn(TAG, "patchBash: only " + freeBytes +
                    " zero bytes after strtab, need " + spaceNeeded + ": " + file);
                return false;
            }

            // Verify these bytes are within a PT_LOAD segment (so the linker can
            // access them once we extend DT_STRSZ to cover them).
            boolean withinLoad = false;
            for (int i = 0; i < loadCnt; i++) {
                long segEnd = loadOff[i] + loadFsz[i];
                if (freeStart >= loadOff[i] && freeStart + spaceNeeded <= segEnd) {
                    withinLoad = true;
                    break;
                }
            }
            if (!withinLoad) {
                Logger.logWarn(TAG, "patchBash: free bytes after strtab not in PT_LOAD: " + file);
                return false;
            }

            // ── 9. Write new strings into the free space ─────────────────────────
            long runpathStrIdx  = strtabSz;                             // offset within strtab
            long wrapperStrIdx  = strtabSz + runpathBytes.length;

            writeBytes(buf, freeStart, runpathBytes);
            writeBytes(buf, freeStart + runpathBytes.length, wrapperNameBytes);

            // Update DT_STRSZ to cover the new strings
            buf.putLong((int)strtabSzOff, strtabSz + spaceNeeded);

            // ── 10. Insert DT_RUNPATH + DT_NEEDED before first existing DT_NEEDED ─
            // Determine insertion index
            int insertAt = (firstNeeded >= 0) ? firstNeeded : (firstNull - 2);
            if (insertAt < 0) insertAt = 0;

            // Shift entries [insertAt .. firstNull-1] → [insertAt+2 .. firstNull+1]
            // (iterate from the end so we don't overwrite source before copying)
            for (int i = firstNull + 1; i >= insertAt + 2; i--) {
                long src = dynFileOffset + (long)(i - 2) * 16;
                long dst = dynFileOffset + (long)i       * 16;
                buf.putLong((int)dst,      buf.getLong((int)src));
                buf.putLong((int)dst + 8,  buf.getLong((int)(src + 8)));
            }

            // Write DT_RUNPATH at insertAt
            long rBase = dynFileOffset + (long)insertAt * 16;
            buf.putLong((int)rBase,      DT_RUNPATH);
            buf.putLong((int)rBase + 8,  runpathStrIdx);

            // Write DT_NEEDED = exec-wrapper at insertAt + 1
            long nBase = dynFileOffset + (long)(insertAt + 1) * 16;
            buf.putLong((int)nBase,      DT_NEEDED);
            buf.putLong((int)nBase + 8,  wrapperStrIdx);

            buf.force();
            Logger.logInfo(TAG, "patchBash: successfully patched " + file);
            return true;

        } catch (Exception e) {
            Logger.logError(TAG, "patchBash: exception for " + file + ": " + e.getMessage());
            return false;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean containsBytes(MappedByteBuffer buf,
                                         long start, int len,
                                         long fileSize, byte[] pattern) {
        long end = Math.min(start + len, fileSize - pattern.length + 1);
        outer:
        for (long i = start; i < end; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (buf.get((int)(i + j)) != pattern[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static void writeBytes(MappedByteBuffer buf, long offset, byte[] data) {
        for (int i = 0; i < data.length; i++) {
            buf.put((int)(offset + i), data[i]);
        }
    }
}
