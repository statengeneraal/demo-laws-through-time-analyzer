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
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.supercsv.cellprocessor.constraint.NotNull;
import org.supercsv.cellprocessor.constraint.UniqueHashCode;
import org.supercsv.cellprocessor.ift.CellProcessor;
import org.supercsv.io.CsvBeanWriter;
import org.supercsv.io.ICsvBeanWriter;
import org.supercsv.prefs.CsvPreference;
import org.tautua.markdownpapers.Markdown;
import org.tautua.markdownpapers.parser.ParseException;

import java.io.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * <p>
 * Class to open a git repository and count the textual changes
 * </p>
 * Created by Maarten on 19-6-14.
 */
public class Analyze {
    public static final Pattern DATE_REGEX = Pattern.compile("[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]");// YYYY-MM-DD
    private static final Pattern IGNORE_CHARACTERS_FOR_NORMATIVE_CHANGE = Pattern.compile("[\\s\\*]");

    /**
     * Sets up the processors for writing to the results a CSV table. There are 4 CSV columns, so 4 processors are defined. All values
     * are converted to Strings before writing (there's no need to convert them), and null values will be written as
     * empty columns (no need to convert them to "").
     *
     * @return the cell processors
     */
    private static CellProcessor[] getProcessors() {
        return new CellProcessor[]{
                new UniqueHashCode(), // date
                new NotNull(), // number of adds
                new NotNull(), // number of modifieds
                new NotNull(), //  number of deletions
        };
    }

    public static void main(String[] args) {
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        try {
            Repository repository = builder.setGitDir(new File("../tools-git-updater/md/.git")) //Path to markdown repository
                    .readEnvironment() // scan environment GIT_* variables
                    .findGitDir() // scan up the file system tree
                    .build();

            // Algorithm used for determining when two texts are different
            DiffAlgorithm diffAlgorithm = DiffAlgorithm.getAlgorithm(repository.getConfig().getEnum(
                    ConfigConstants.CONFIG_DIFF_SECTION, null,
                    ConfigConstants.CONFIG_KEY_ALGORITHM,
                    DiffAlgorithm.SupportedAlgorithm.HISTOGRAM));

            //Do a walk along the commit graph, starting at a certain commit
            RevWalk walk = new RevWalk(repository);
            walk.markStart(getCommit(walk, "1cebd145ed60bf3103c772df1c7c540d79a4f559")); // Start at 20**-**-**//TODO starts somewhere
            RevCommit newCommit = walk.next();
            RevCommit oldCommit = walk.next();

            Map<String, ChangesCounter> changesCounterForDates = new HashMap<String, ChangesCounter>();
            while (oldCommit != null) {
                String authorDate = newCommit.getFullMessage().trim();
                //        String oldDate = oldCommit.getFullMessage().trim();
                //        System.out.println(oldDate + " vs. " + newDate);
                ChangesCounter changesCounter = getChangesCounter(changesCounterForDates, authorDate);
                AbstractTreeIterator newTreeParser = prepareTreeParser(repository, walk, newCommit);
                AbstractTreeIterator oldTreeParser = prepareTreeParser(repository, walk, oldCommit);
                handleDiff(repository, diffAlgorithm, oldTreeParser, newTreeParser, changesCounter);

                //Prepare for next iteration
                newCommit = oldCommit;
                ///Skip commits that are not formatted YYYY-MM-DD
                do {
                    oldCommit = walk.next();
                }
                while (oldCommit != null && !DATE_REGEX.matcher(oldCommit.getFullMessage().trim()).matches());
            }
            repository.close();

            // Write results to table
            writeResultsToTable(changesCounterForDates);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (GitAPIException e) {
            e.printStackTrace();
        } catch (ParseException e) {
            e.printStackTrace();
        }
    }

    /**
     * Count text add / modifications / deletions for this particular date
     */
    private static void handleDiff(Repository repository, DiffAlgorithm diffAlgorithm, AbstractTreeIterator oldTreeParser, AbstractTreeIterator newTreeParser, ChangesCounter changesCounter) throws IOException, GitAPIException, ParseException {
        // return a list of diff entries
        List<DiffEntry> diff = new Git(repository).diff().
                setOldTree(oldTreeParser).
                setNewTree(newTreeParser).
                call();
        for (DiffEntry entry : diff) {
//                    entry.getOldPath().endsWith("README.md")
            switch (entry.getChangeType()) {
                case MODIFY:
                    changesCounter.addIfNormativeContentModification(diffAlgorithm, repository, entry);
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

    private static void writeResultsToTable(Map<String, ChangesCounter> changesCounterForDates) throws IOException {
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

    private static ChangesCounter getChangesCounter(Map<String, ChangesCounter> changesCounterForDate, String date) {
        ChangesCounter changesCounter = changesCounterForDate.get(date);
        if (changesCounter == null) {
            changesCounter = new ChangesCounter(date);
            changesCounterForDate.put(date, changesCounter);
        }
        return changesCounter;
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

    public static boolean hasNormativeContentChange(MyDiffFormatter.FormatResult formatResult, Edit edit) throws ParseException {
        // Normalise the texts (e.g., strip spaces and anchors) to see if the actual content changed
        String normalisedA = getNormalisedString(formatResult.a, edit.getBeginA(), edit.getEndA());
        String normalisedB = getNormalisedString(formatResult.b, edit.getBeginB(), edit.getEndB());

//        if (!normalisedA.equals(normalisedB)) {
//            System.out.println(" NormativeContent change found");
//        }
        return !normalisedA.equals(normalisedB);
    }

    private static String getNormalisedString(RawText rawText, int begin, int end) throws ParseException {
        StringBuilder stringBuilder = new StringBuilder(50);
        for (int i = begin; i < end; i++) {
            stringBuilder.append(rawText.getString(i));
        }
        StringReader in = new StringReader(stringBuilder.toString());
        StringWriter out = new StringWriter();

        Markdown md = new Markdown();
        md.transform(in, out);

        Document html = Jsoup.parse(out.toString());
        return IGNORE_CHARACTERS_FOR_NORMATIVE_CHANGE.matcher(html.text()).replaceAll("");
    }

    @SuppressWarnings("UnusedDeclaration")
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
        public void addIfNormativeContentModification(DiffAlgorithm diffAlgorithm, Repository repository, DiffEntry diffEntry) throws IOException, ParseException {
            if (diffEntry.getChangeType() != DiffEntry.ChangeType.MODIFY) {
                throw new IllegalArgumentException();
            }

            MyDiffFormatter formatter = new MyDiffFormatter(System.out);
            formatter.setContext(0);
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
