import javafx.util.Pair;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.*;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.supercsv.cellprocessor.Optional;
import org.supercsv.cellprocessor.constraint.NotNull;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.io.CsvBeanWriter;
import org.supercsv.io.ICsvBeanWriter;
import org.supercsv.prefs.CsvPreference;
import org.tautua.markdownpapers.Markdown;
import org.tautua.markdownpapers.parser.ParseException;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>
 * Class to open a git repository and count the textual changes
 * </p>
 * Created by Maarten on 19-6-14.
 */
public class Analyze {
    public static final Pattern DATE_REGEX = Pattern.compile("[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]");// YYYY-MM-DD
    public static final Pattern BWB_FILE_REGEX = Pattern.compile("(.*[/\\\\])*(BWB[^/^\\\\]+)([/\\\\].*)");// YYYY-MM-DD
    private static final Pattern IGNORE_CHARACTERS_FOR_NORMATIVE_CHANGE = Pattern.compile("[\\s\\*]");

    /**
     * This is the tag of the first commit for which a law was added (February 13 1815). However, we do not track legislative modifications since this time, so we don't use this commit as a starting point
     */
    @SuppressWarnings("UnusedDeclaration")
    private static final String FIRST_COMMIT = "f76c2addde6c75a3514a639a5a5e522bfac3402b";
    /**
     * The tag of the first commit in which a law was changed. This marks the moment we have started to track legislative changes.
     */
    @SuppressWarnings("UnusedDeclaration")
    private static final String FIRST_COMMIT_WITH_EDIT = "???";
    private static final String COMMIT_20140926 = "b9c3ad8a2888bf0135df467aace4e8839237ac16";
    //    private static final String COMMIT_20140926 = "03df81bbe070c255f4fdfaa2fc654b7e409d7fb8";
    private static final File PATH_TO_GIT_REPO = new File("../wetten-tools/laws-markdown/.git/");
    //    private static final File PATH_TO_GIT_REPO = new File("../wetten-tools/test-repo/.git/");
    private static DiffAlgorithm diffAlgorithm;

    /**
     * Sets up the processors for writing to the results a CSV table. There are 4 CSV columns, so 4 processors are
     * defined. All values are converted to Strings before writing (there's no need to convert them), and null values
     * will be written as empty columns (no need to convert them to "").
     *
     * @return the cell processors
     */
    private static CellProcessor[] getProcessors() {
        return new CellProcessor[]{
                new NotNull(), // date
                new NotNull(), // BWB ID
                new NotNull(), // type of modification
                new Optional(), // before
                new Optional(), // after
                new Optional(), // is_add
                new Optional(), // is_modify
                new Optional(), // is_delete
        };
    }

    /**
     * Start the script
     */
    public static void main(String[] args) {
        // Create git repo object
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        try {
            Repository repository = builder.setGitDir(PATH_TO_GIT_REPO) //Path to markdown git repository
                    .readEnvironment() // scan environment GIT_* variables
                    .findGitDir() // scan up the file system tree
                    .build();

            // Set algorithm used for determining when two texts are different
            diffAlgorithm = DiffAlgorithm.getAlgorithm(
                    repository.getConfig().getEnum(
                            ConfigConstants.CONFIG_DIFF_SECTION,
                            null,
                            ConfigConstants.CONFIG_KEY_ALGORITHM,
                            DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
            );

            //Do a walk along the commit graph, starting at a certain commit
            RevWalk walk = new RevWalk(repository);
            RevCommit newCommit = getCommit(walk, COMMIT_20140926);

            walk.markStart(newCommit); // Start at last commit (at time of writing), and work into the past
            RevCommit oldCommit = walk.next();

            Map<String, List<Change>> changesForDate = new HashMap<String, List<Change>>();
            MyDiffFormatter formatter = new MyDiffFormatter(System.out);
            while (newCommit != null) {
                String authorDate = newCommit.getFullMessage().trim();
                System.out.println("New commit date: " + newCommit.getFullMessage().trim());
//                String oldDate = "none";
//                if (oldCommit != null) {
//                    oldDate = oldCommit.getFullMessage().trim();
//                }
//                System.out.println("Old commit date: " + oldDate);
                List<Change> changes = changesForDate.get(authorDate);
                //Create changes list if this date did not have one already
                if (changes == null) {
                    changes = new ArrayList<Change>(15);
                    changesForDate.put(authorDate, changes);
                }

                // List differences for new commit
                AbstractTreeIterator newTreeParser = prepareTreeParser(repository, walk, newCommit);
                AbstractTreeIterator oldTreeParser;
                if (oldCommit != null) {
                    oldTreeParser = prepareTreeParser(repository, walk, oldCommit);
                } else {
                    oldTreeParser = new EmptyTreeIterator();
                }

                // return a list of diff entries
                formatter.setRepository(repository);
                List<DiffEntry> diffs = formatter.scan(oldTreeParser, newTreeParser);
                addEvents(formatter, diffs, authorDate, changes, repository);

                //Prepare for next iteration
                do {
                    newCommit = oldCommit;
                    oldCommit = walk.next();
                }
                //Skip commits that are not formatted YYYY-MM-DD
                while (newCommit != null && !DATE_REGEX.matcher(newCommit.getFullMessage().trim()).matches());
            }
            repository.close();

            //Write results to table
            writeResultsToTable(changesForDate);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void addEvents(MyDiffFormatter formatter, List<DiffEntry> diff, String date, List<Change> changes, Repository repository) throws IOException, GitAPIException, ParseException {
        // Handle diffs
        //TODO get authordate from diffentry
        for (DiffEntry entry : diff) {
            String path = entry.getNewPath();
            if (path == null || path.equals("/dev/null")) {
                path = entry.getOldPath();
            }

            Matcher m = BWB_FILE_REGEX.matcher(path);
            boolean matches = m.find();
            if (matches) {
                String bwbId = m.group(2);

                String before = null;
                String after = null;
                switch (entry.getChangeType()) {
                    case MODIFY:
                        MyDiffFormatter.FormatResult formatResult = formatter.getFormatResult(entry); //TODO find another way to get raw text, this isn't efficient (?)
                        try {
                            if (formatResult.a == null | formatResult.b == null) {
                                EditList edits = diffAlgorithm.diff(RawTextComparator.WS_IGNORE_ALL, formatResult.a, formatResult.b);
                                for (Edit edit : edits) {
//                                Pair<String, String> beforeAfter = getBeforeAndAfter(formatResult, edit);
//                                before = beforeAfter.getKey();
//                                after = beforeAfter.getValue();

                                    //Check if not just whitespace / metadata that has changed
                                    if (hasNormativeContentChange(formatResult, edit)) {
                                        changes.add(new Change(date, bwbId, "modify", before, after));
                                        break;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            System.err.println("Could not process an edit for " + date + "; " + bwbId);
                            System.err.println("A: " + formatResult.a);
                            System.err.println("B: " + formatResult.b);
                        }
                        break;
                    case ADD:
                        //NOTE: Of course, the entire text content is added, but that will make out output way too wordy
                        changes.add(new Change(date, bwbId, "add", before, after));
                        break;
                    case DELETE:
                        //NOTE: Of course, the entire text content is deleted, but that will make out output way too wordy
                        changes.add(new Change(date, bwbId, "delete", before, after));
                        break;
                    case RENAME:
                        System.err.println("WARNING: Renames should not occur (happened from " + entry.getOldPath() + " to " + entry.getNewPath());
                        break;
                    case COPY:
                        System.err.println("WARNING: Copies should not occur (happened from " + entry.getOldPath() + " to " + entry.getNewPath());
                        break;
                }
                //System.out.println("Entry: " + entry + ", from: " + entry.getOldId() + ", to: " + entry.getNewId() + ". Type: " + entry.getChangeType());
            } else {
                //TODO why do changes to index.json appear? They do not appear in YYYY-MM-DD commits :-/
                System.err.println("Could not find BWB ID in " + path + "; " + date);
            }
        }
    }


    /**
     * Count text add / modifications / deletions for this particular date
     */
    @Deprecated
    private static void countEventsInDiff(Repository repository, AbstractTreeIterator
            oldTreeParser, AbstractTreeIterator newTreeParser, ChangesCounter changesCounter) throws
            IOException, GitAPIException, ParseException {
        // return a list of diff entries
        List<DiffEntry> diff = new Git(repository).diff().
                setOldTree(oldTreeParser).
                setNewTree(newTreeParser).
                call();
        for (DiffEntry entry : diff) {
//                    entry.getOldPath().endsWith("README.md")
            switch (entry.getChangeType()) {
                case MODIFY:
                    changesCounter.addIfNormativeContentModification(repository, entry);
                case ADD:
                case DELETE:
                    changesCounter.add(entry.getChangeType());
                    break;
                case RENAME:
                    System.err.println("WARNING: Renames should not occur (happened from " + entry.getOldPath() + " to " + entry.getNewPath());
                    break;
                case COPY:
                    System.err.println("WARNING: Copies should not occur (happened from " + entry.getOldPath() + " to " + entry.getNewPath());
                    break;
            }
            //System.out.println("Entry: " + entry + ", from: " + entry.getOldId() + ", to: " + entry.getNewId() + ". Type: " + entry.getChangeType());
        }
    }


    private static void writeResultsToTable(Map<String, List<Change>> changesForDates) throws IOException {
        ICsvBeanWriter beanWriter = null;
        try {
            beanWriter = new CsvBeanWriter(new FileWriter("result.csv"),
                    CsvPreference.STANDARD_PREFERENCE);
            final String[] header = new String[]{"Date", "BWB ID", "Modification type", "Before", "After", "Adds", "Modifies", "Deletes"};
            final CellProcessor[] processors = getProcessors();

            beanWriter.writeHeader(header);
            for (List<Change> changes : changesForDates.values()) {
                for (Change change : changes) {
                    beanWriter.write(change, Change.TABLE_MAPPING, processors);
                }
            }
        } finally {
            if (beanWriter != null) {
                beanWriter.close();
            }
        }
    }

    private static void writeChangesCountToTable(Map<String, ChangesCounter> changesCounterForDates) throws IOException {
        ICsvBeanWriter beanWriter = null;
        try {
            beanWriter = new CsvBeanWriter(new FileWriter("result.csv"),
                    CsvPreference.STANDARD_PREFERENCE);
            final String[] header = new String[]{"Date", "Documents added", "Documents modified", "Documents deleted"};
            final CellProcessor[] processors = getProcessors();

            beanWriter.writeHeader(header);
            for (ChangesCounter date : changesCounterForDates.values()) {
                beanWriter.write(date, ChangesCounter.TABLE_MAPPING, processors);
            }
        } finally {
            if (beanWriter != null) {
                beanWriter.close();
            }
        }
    }


    private static RevCommit getCommit(RevWalk walk, String objectId) throws IOException {
        // from the commit we can build the tree which allows us to construct the TreeParser
        return walk.parseCommit(ObjectId.fromString(objectId));
    }

    private static AbstractTreeIterator prepareTreeParser(Repository repository, RevWalk walk, RevCommit commit) throws IOException {
        RevTree tree = walk.parseTree(commit.getTree().getId());

        CanonicalTreeParser oldTreeParser = new CanonicalTreeParser();
        ObjectReader oldReader = repository.newObjectReader();
        try {
            oldTreeParser.reset(oldReader, tree.getId());
        } finally {
            oldReader.release();
        }
        return oldTreeParser;
    }

    public static Pair<String, String> getBeforeAndAfter(MyDiffFormatter.FormatResult formatResult, Edit edit) throws ParseException {
        String a = getStringFromRawText(formatResult.a, edit.getBeginA(), edit.getEndA());
        String b = getStringFromRawText(formatResult.b, edit.getBeginB(), edit.getEndB());
        return new Pair<String, String>(a, b);
    }

    public static boolean hasNormativeContentChange(MyDiffFormatter.FormatResult formatResult, Edit edit) throws ParseException {
        // Normalise the texts (e.g., strip spaces and anchors) to see if the actual content changed
        String normalisedA = getNormalisedString(getStringFromRawText(formatResult.a, edit.getBeginA(), edit.getEndA()));
        String normalisedB = getNormalisedString(getStringFromRawText(formatResult.b, edit.getBeginB(), edit.getEndB()));

//        if (!normalisedA.equals(normalisedB)) {
//            System.out.println(" NormativeContent change found");
//        }
        return !normalisedA.equals(normalisedB);
    }

    private static String getStringFromRawText(RawText rawText, int begin, int end) {
        StringBuilder stringBuilder = new StringBuilder(50);
        for (int i = begin; i < end; i++) {
            stringBuilder.append(rawText.getString(i));
        }
        return stringBuilder.toString();
    }

    private static String getNormalisedString(String strMd) throws ParseException {
        StringReader in = new StringReader(strMd);
        StringWriter out = new StringWriter();

        Markdown md = new Markdown();
        md.transform(in, out);

        Document html = Jsoup.parse(out.toString());
        return IGNORE_CHARACTERS_FOR_NORMATIVE_CHANGE.matcher(html.text()).replaceAll("");
    }

    @Deprecated
    private static ChangesCounter getChangesCounter(Map<String, ChangesCounter> changesCounterForDate, String date) {
        ChangesCounter changesCounter = changesCounterForDate.get(date);
        if (changesCounter == null) {
            changesCounter = new ChangesCounter(date);
            changesCounterForDate.put(date, changesCounter);
        }
        return changesCounter;
    }

    @SuppressWarnings("UnusedDeclaration")
    public static class Change {
        public static final String[] TABLE_MAPPING = new String[]{"date", "bwbId", "type", "before", "after", "isAdd", "isModify", "isDelete"};
        public String date;
        public String bwbId;
        public String type;
        public String before;
        public String after;
        public int isAdd = 0;
        public int isModify = 0;
        public int isDelete = 0;

        public Change(String date, String bwbId, String type, String before, String after) {
            this.date = date;
            this.bwbId = bwbId;
            this.type = type;
            this.before = before;
            this.after = after;
            if (type.equals("add")) {
                this.isAdd = 1;
            } else if (type.equals("modify")) {
                this.isModify = 1;
            } else if (type.equals("delete")) {
                this.isDelete = 1;
            } else {
                throw new IllegalArgumentException();
            }
        }

        public int getIsAdd() {
            return isAdd;
        }

        public int getIsDelete() {
            return isDelete;
        }

        public int getIsModify() {
            return isModify;
        }

        public void setIsAdd(int isAdd) {
            this.isAdd = isAdd;
        }

        public void setIsDelete(int isDelete) {
            this.isDelete = isDelete;
        }

        public void setIsModify(int isModify) {
            this.isModify = isModify;
        }

        public String getDate() {
            return date;
        }

        public void setDate(String date) {
            this.date = date;
        }

        public String getBwbId() {
            return bwbId;
        }

        public void setBwbId(String bwbId) {
            this.bwbId = bwbId;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getBefore() {
            return before;
        }

        public void setBefore(String before) {
            this.before = before;
        }

        public String getAfter() {
            return after;
        }

        public void setAfter(String after) {
            this.after = after;
        }


    }

    /**
     * Counts the number of adds, modifies and deletes for a certain date
     */
    @Deprecated
    private static class ChangesCounter {
        public static final String[] TABLE_MAPPING = new String[]{"date", "adds", "modifies", "deletes"};
        public final String date;
        public int adds = 0;
        public int modifies = 0;
        public int deletes = 0;

        public ChangesCounter(String date) {
            this.date = date;
        }

        public String getDate() {
            return date;
        }

        public int getAdds() {
            return adds;
        }

        public void setAdds(int adds) {
            this.adds = adds;
        }

        public int getModifies() {
            return modifies;
        }

        public void setModifies(int modifies) {
            this.modifies = modifies;
        }

        public int getDeletes() {
            return deletes;
        }

        public void setDeletes(int deletes) {
            this.deletes = deletes;
        }

        public void add(DiffEntry.ChangeType type) {
            switch (type) {
                case ADD:
                    adds++;
                    break;
                case MODIFY:
                    modifies++;
                    break;
                case DELETE:
                    deletes++;
                    break;
                case RENAME:
                case COPY:
                    throw new IllegalArgumentException();
            }
        }


        /**
         * Adds one to <i>adds</i> if this diff contains modifications that are not whitespace or markup
         */
        public void addIfNormativeContentModification(Repository repository, DiffEntry diffEntry) throws IOException, ParseException {
            if (diffEntry.getChangeType() != DiffEntry.ChangeType.MODIFY) {
                throw new IllegalArgumentException();
            }

            MyDiffFormatter formatter = new MyDiffFormatter(System.out);
            formatter.setRepository(repository);

            MyDiffFormatter.FormatResult formatResult = formatter.getFormatResult(diffEntry);//TODO find another way to get raw text, this isn't efficient (?)
            EditList edits = diffAlgorithm.diff(RawTextComparator.WS_IGNORE_ALL, formatResult.a, formatResult.b);

            for (Edit edit : edits) {
                if (hasNormativeContentChange(formatResult, edit)) {
                    adds++;
                    return;
                }
            }
        }
    }
}
