/*
 * Copyright (C) 2009, Google Inc.
 * Copyright (C) 2008-2009, Johannes E. Schindelin <johannes.schindelin@gmx.de>
 * and other copyright owners as documented in the project's IP log.
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */


import org.eclipse.jgit.diff.*;
import org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.patch.FileHeader.PatchType;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.QuotedString;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;

import static org.eclipse.jgit.diff.DiffEntry.ChangeType.*;
import static org.eclipse.jgit.diff.DiffEntry.Side.NEW;
import static org.eclipse.jgit.diff.DiffEntry.Side.OLD;
import static org.eclipse.jgit.lib.Constants.encode;
import static org.eclipse.jgit.lib.Constants.encodeASCII;
import static org.eclipse.jgit.lib.FileMode.GITLINK;

/**
 * Format a Git style patch script.
 */
public class MyDiffFormatter {
    private static final int DEFAULT_BINARY_FILE_THRESHOLD = PackConfig.DEFAULT_BIG_FILE_THRESHOLD;
    private static final byte[] noNewLine = encodeASCII("\\ No newline at end of file\n"); //$NON-NLS-1$
    /**
     * Magic return content indicating it is empty or no content present.
     */
    private static final byte[] EMPTY = new byte[]{};
    /**
     * Magic return indicating the content is binary.
     */
    private static final byte[] BINARY = new byte[]{};
    private final OutputStream out;
    private Repository db;
    private ObjectReader reader;
    private DiffConfig diffCfg;
    private int context = 3;
    private DiffAlgorithm diffAlgorithm;
    private RawTextComparator comparator = RawTextComparator.WS_IGNORE_ALL;
    private String oldPrefix = "a/"; //$NON-NLS-1$

    private String newPrefix = "b/"; //$NON-NLS-1$

    private TreeFilter pathFilter = TreeFilter.ALL;

    private RenameDetector renameDetector;

    private ProgressMonitor progressMonitor;

    private ContentSource.Pair source;

    /**
     * Create a new formatter with a default level of context.
     *
     * @param out the stream the formatter will write line data to. This stream
     *            should have buffering arranged by the caller, as many small
     *            writes are performed to it.
     */
    public MyDiffFormatter(OutputStream out) {
        this.out = out;
    }

    private static void writeGitLinkDiffText(OutputStream o, DiffEntry ent)
            throws IOException {
        if (ent.getOldMode() == GITLINK) {
            o.write(encodeASCII("-Subproject commit " + ent.getOldId().name() //$NON-NLS-1$
                    + "\n")); //$NON-NLS-1$
        }
        if (ent.getNewMode() == GITLINK) {
            o.write(encodeASCII("+Subproject commit " + ent.getNewId().name() //$NON-NLS-1$
                    + "\n")); //$NON-NLS-1$
        }
    }

    private static String quotePath(String name) {
        return QuotedString.GIT_PATH.quote(name);
    }

    /**
     * Set the progress monitor for long running rename detection.
     *
     * @param pm progress monitor to receive rename detection status through.
     */
    @SuppressWarnings("UnusedDeclaration")
    public void setProgressMonitor(ProgressMonitor pm) {
        progressMonitor = pm;
    }

    /**
     * Set the repository the formatter can load object contents from.
     * <p/>
     * Once a repository has been set, the formatter must be released to ensure
     * the internal ObjectReader is able to release its resources.
     *
     * @param repository source repository holding referenced objects.
     */
    public void setRepository(Repository repository) {
        if (reader != null)
            reader.release();

        db = repository;
        reader = db.newObjectReader();
        diffCfg = db.getConfig().get(DiffConfig.KEY);

        ContentSource cs = ContentSource.create(reader);
        source = new ContentSource.Pair(cs, cs);

        DiffConfig dc = db.getConfig().get(DiffConfig.KEY);
        if (dc.isNoPrefix()) {
            setOldPrefix(""); //$NON-NLS-1$
            setNewPrefix(""); //$NON-NLS-1$
        }
        setDetectRenames(dc.isRenameDetectionEnabled());

        diffAlgorithm = DiffAlgorithm.getAlgorithm(db.getConfig().getEnum(
                ConfigConstants.CONFIG_DIFF_SECTION, null,
                ConfigConstants.CONFIG_KEY_ALGORITHM,
                SupportedAlgorithm.HISTOGRAM));

    }

    /**
     * Change the number of lines of context to display.
     *
     * @param lineCount number of lines of context to see before the first
     *                  modification and after the last modification within a hunk of
     *                  the modified file.
     */
    public void setContext(final int lineCount) {
        if (lineCount < 0)
            throw new IllegalArgumentException(
                    JGitText.get().contextMustBeNonNegative);
        context = lineCount;
    }

    /**
     * Set the prefix applied in front of old file paths.
     *
     * @param prefix the prefix in front of old paths. Typically this is the
     *               standard string {@code "a/"}, but may be any prefix desired by
     *               the caller. Must not be null. Use the empty string to have no
     *               prefix at all.
     */
    public void setOldPrefix(String prefix) {
        oldPrefix = prefix;
    }

    /**
     * Set the prefix applied in front of new file paths.
     *
     * @param prefix the prefix in front of new paths. Typically this is the
     *               standard string {@code "b/"}, but may be any prefix desired by
     *               the caller. Must not be null. Use the empty string to have no
     *               prefix at all.
     */
    public void setNewPrefix(String prefix) {
        newPrefix = prefix;
    }

    /**
     * Enable or disable rename detection.
     * <p/>
     * Before enabling rename detection the repository must be set with
     * {@link #setRepository(Repository)}. Once enabled the detector can be
     * configured away from its defaults by obtaining the instance directly from
     * {@link #getRenameDetector()} and invoking configuration.
     *
     * @param on if rename detection should be enabled.
     */
    public void setDetectRenames(boolean on) {
        if (on && renameDetector == null) {
            assertHaveRepository();
            renameDetector = new RenameDetector(db);
        } else if (!on)
            renameDetector = null;
    }

    /**
     * @return the rename detector if rename detection is enabled.
     */
    public RenameDetector getRenameDetector() {
        return renameDetector;
    }

    private String format(AbbreviatedObjectId id) {
        if (id.isComplete() && db != null) {
            try {
                int abbreviationLength = 7;
                id = reader.abbreviate(id.toObjectId(), abbreviationLength);
            } catch (IOException cannotAbbreviate) {
                // Ignore this. We'll report the full identity.
            }
        }
        return id.name();
    }

    public FormatResult getFormatResult(DiffEntry ent) throws IOException {
        final FormatResult res = new FormatResult();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        final EditList editList;
        final FileHeader.PatchType type;

        formatHeader(buf, ent);

        if (ent.getOldMode() == GITLINK || ent.getNewMode() == GITLINK) {
            formatOldNewPaths(buf, ent);
            writeGitLinkDiffText(buf, ent);
            editList = new EditList();
            type = PatchType.UNIFIED;

        } else if (ent.getOldId() == null || ent.getNewId() == null) {
            // Content not changed (e.g. only mode, pure rename)
            editList = new EditList();
            type = PatchType.UNIFIED;

        } else {
            assertHaveRepository();

            byte[] aRaw = open(OLD, ent);
            byte[] bRaw = open(NEW, ent);

            if (aRaw == BINARY || bRaw == BINARY //
                    || RawText.isBinary(aRaw) || RawText.isBinary(bRaw)) {
                formatOldNewPaths(buf, ent);
                buf.write(encodeASCII("Binary files differ\n")); //$NON-NLS-1$
                editList = new EditList();
                type = PatchType.BINARY;

            } else {
                res.a = new RawText(aRaw);
                res.b = new RawText(bRaw);
                editList = diff(res.a, res.b);
                type = PatchType.UNIFIED;

                switch (ent.getChangeType()) {
                    case RENAME:
                    case COPY:
                        if (!editList.isEmpty())
                            formatOldNewPaths(buf, ent);
                        break;

                    default:
                        formatOldNewPaths(buf, ent);
                        break;
                }
            }
        }

        res.header = new FileHeader(buf.toByteArray(), editList, type);
        return res;
    }

    public EditList diff(RawText a, RawText b) {
        return diffAlgorithm.diff(comparator, a, b);
    }

    private void assertHaveRepository() {
        if (db == null)
            throw new IllegalStateException(JGitText.get().repositoryIsRequired);
    }

    private byte[] open(DiffEntry.Side side, DiffEntry entry)
            throws IOException {
        if (entry.getMode(side) == FileMode.MISSING)
            return EMPTY;

        if (entry.getMode(side).getObjectType() != Constants.OBJ_BLOB)
            return EMPTY;

        AbbreviatedObjectId id = entry.getId(side);
        if (!id.isComplete()) {
            Collection<ObjectId> ids = reader.resolve(id);
            if (ids.size() == 1) {
                throw new IllegalStateException();
//                id = AbbreviatedObjectId.fromObjectId(ids.iterator().next());
//                switch (side) {
//                    case OLD:
//                        entry.oldId = id;
//                        break;
//                    case NEW:
//                        entry.newId = id;
//                        break;
//                }
            } else if (ids.size() == 0)
                throw new MissingObjectException(id, Constants.OBJ_BLOB);
            else
                throw new AmbiguousObjectException(id, ids);
        }

        try {
            ObjectLoader ldr = source.open(side, entry);
            int binaryFileThreshold = DEFAULT_BINARY_FILE_THRESHOLD;
            return ldr.getBytes(binaryFileThreshold);

        } catch (LargeObjectException.ExceedsLimit overLimit) {
            return BINARY;

        } catch (LargeObjectException.ExceedsByteArrayLimit overLimit) {
            return BINARY;

        } catch (LargeObjectException.OutOfMemory tooBig) {
            return BINARY;

        } catch (LargeObjectException tooBig) {
            tooBig.setObjectId(id.toObjectId());
            throw tooBig;
        }
    }

    /**
     * Output the first header line
     *
     * @param o       The stream the formatter will write the first header line to
     * @param type    The {@link ChangeType}
     * @param oldPath old path to the file
     * @param newPath new path to the file
     * @throws IOException the stream threw an exception while writing to it.
     */
    protected void formatGitDiffFirstHeaderLine(ByteArrayOutputStream o,
                                                final ChangeType type, final String oldPath, final String newPath)
            throws IOException {
        o.write(encodeASCII("diff --git ")); //$NON-NLS-1$
        o.write(encode(quotePath(oldPrefix + (type == ADD ? newPath : oldPath))));
        o.write(' ');
        o.write(encode(quotePath(newPrefix
                + (type == DELETE ? oldPath : newPath))));
        o.write('\n');
    }

    private void formatHeader(ByteArrayOutputStream o, DiffEntry ent)
            throws IOException {
        final ChangeType type = ent.getChangeType();
        final String oldp = ent.getOldPath();
        final String newp = ent.getNewPath();
        final FileMode oldMode = ent.getOldMode();
        final FileMode newMode = ent.getNewMode();

        formatGitDiffFirstHeaderLine(o, type, oldp, newp);

        if ((type == MODIFY || type == COPY || type == RENAME)
                && !oldMode.equals(newMode)) {
            o.write(encodeASCII("old mode ")); //$NON-NLS-1$
            oldMode.copyTo(o);
            o.write('\n');

            o.write(encodeASCII("new mode ")); //$NON-NLS-1$
            newMode.copyTo(o);
            o.write('\n');
        }

        switch (type) {
            case ADD:
                o.write(encodeASCII("new file mode ")); //$NON-NLS-1$
                newMode.copyTo(o);
                o.write('\n');
                break;

            case DELETE:
                o.write(encodeASCII("deleted file mode ")); //$NON-NLS-1$
                oldMode.copyTo(o);
                o.write('\n');
                break;

            case RENAME:
                o.write(encodeASCII("similarity index " + ent.getScore() + "%")); //$NON-NLS-1$ //$NON-NLS-2$
                o.write('\n');

                o.write(encode("rename from " + quotePath(oldp))); //$NON-NLS-1$
                o.write('\n');

                o.write(encode("rename to " + quotePath(newp))); //$NON-NLS-1$
                o.write('\n');
                break;

            case COPY:
                o.write(encodeASCII("similarity index " + ent.getScore() + "%")); //$NON-NLS-1$ //$NON-NLS-2$
                o.write('\n');

                o.write(encode("copy from " + quotePath(oldp))); //$NON-NLS-1$
                o.write('\n');

                o.write(encode("copy to " + quotePath(newp))); //$NON-NLS-1$
                o.write('\n');
                break;

            case MODIFY:
                if (0 < ent.getScore()) {
                    o.write(encodeASCII("dissimilarity index " //$NON-NLS-1$
                            + (100 - ent.getScore()) + "%")); //$NON-NLS-1$
                    o.write('\n');
                }
                break;
        }

        if (ent.getOldId() != null && !ent.getOldId().equals(ent.getNewId())) {
            formatIndexLine(o, ent);
        }
    }

    /**
     * @param o   the stream the formatter will write line data to
     * @param ent the DiffEntry to create the FileHeader for
     * @throws IOException writing to the supplied stream failed.
     */
    protected void formatIndexLine(OutputStream o, DiffEntry ent)
            throws IOException {
        o.write(encodeASCII("index " // //$NON-NLS-1$
                + format(ent.getOldId()) //
                + ".." // //$NON-NLS-1$
                + format(ent.getNewId())));
        if (ent.getOldMode().equals(ent.getNewMode())) {
            o.write(' ');
            ent.getNewMode().copyTo(o);
        }
        o.write('\n');
    }

    private void formatOldNewPaths(ByteArrayOutputStream o, DiffEntry ent)
            throws IOException {
        if (ent.getOldId().equals(ent.getNewId()))
            return;

        final String oldp;
        final String newp;

        switch (ent.getChangeType()) {
            case ADD:
                oldp = DiffEntry.DEV_NULL;
                newp = quotePath(newPrefix + ent.getNewPath());
                break;

            case DELETE:
                oldp = quotePath(oldPrefix + ent.getOldPath());
                newp = DiffEntry.DEV_NULL;
                break;

            default:
                oldp = quotePath(oldPrefix + ent.getOldPath());
                newp = quotePath(newPrefix + ent.getNewPath());
                break;
        }

        o.write(encode("--- " + oldp + "\n")); //$NON-NLS-1$ //$NON-NLS-2$
        o.write(encode("+++ " + newp + "\n")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private int findCombinedEnd(final List<Edit> edits, final int i) {
        int end = i + 1;
        while (end < edits.size()
                && (combineA(edits, end) || combineB(edits, end)))
            end++;
        return end - 1;
    }

    private boolean combineA(final List<Edit> e, final int i) {
        return e.get(i).getBeginA() - e.get(i - 1).getEndA() <= 2 * context;
    }

    private boolean combineB(final List<Edit> e, final int i) {
        return e.get(i).getBeginB() - e.get(i - 1).getEndB() <= 2 * context;
    }

    protected static class FormatResult {
        FileHeader header;

        RawText a;

        RawText b;
    }
}
