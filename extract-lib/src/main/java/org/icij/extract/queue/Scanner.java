package org.icij.extract.queue;

import org.icij.concurrent.ExecutorProxy;
import org.icij.concurrent.SealableLatch;
import org.icij.event.Notifiable;
import org.icij.extract.document.Document;
import org.icij.extract.document.DocumentFactory;
import org.icij.extract.io.file.DosHiddenFileMatcher;
import org.icij.extract.io.file.PosixHiddenFileMatcher;
import org.icij.extract.io.file.SystemFileMatcher;
import org.icij.task.Options;
import org.icij.task.StringOptionParser;
import org.icij.task.annotation.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.icij.extract.queue.ScannerVisitor.FOLLOW_SYMLINKS;
import static org.icij.extract.queue.ScannerVisitor.MAX_DEPTH;

/**
 * Scanner for scanning the directory tree starting at a given path.
 *
 * Each time {@link #scan} is called, the job is put in an unbounded queue and executed in serial. This makes sense as
 * it's usually the file system which is a bottleneck and not the CPU, so parallelization won't help.
 *
 * The {@link #scan} method is non-blocking, which is useful for creating parallelized producer-consumer setups, where
 * files are processed as they're scanned.
 *
 * Encountered documents are put in a given queue. This is a classic producer, putting elements in a queue which are
 * then extracted by a consumer.
 *
 * The queue should be bounded, to avoid the scanner filling up memory, but the bound should be high enough to create a
 * significant buffer between the scanner and the consumer.
 *
 * Documents are pushed into the queue synchronously and if the queue is bounded, only when a space becomes available.
 *
 * This implementation is thread-safe.
 *
 * @since 1.0.0-beta
 */
@Option(name = "includeHiddenFiles", description = "Don't ignore hidden files. On DOS file systems, this" +
		" means all files or directories with the \"hidden\" file attribute. On all other file systems, this means " +
		"all file or directories starting with a dot. Hidden files are ignored by default.")
@Option(name = "includeOSFiles", description = "Include files and directories generated by common " +
		"operating systems. This includes \"Thumbs.db\" and \".DS_Store\". The list is not determined by the current " +
		"operating system. OS-generated files are ignored by default.")
@Option(name = "includePattern", description = "Glob pattern for matching files e.g. \"**/*.{tif,pdf}\". " +
		"Files not matching the pattern will be ignored.", parameter = "pattern")
@Option(name = "excludePattern", description = "Glob pattern for excluding files and directories. Files " +
		"and directories matching the pattern will be ignored.", parameter = "pattern")
@Option(name = FOLLOW_SYMLINKS, description = "Follow symbolic links, which are not followed by default.")
@Option(name = MAX_DEPTH, description = "The maximum depth to which the scanner will recurse.", parameter = "integer")
public class Scanner extends ExecutorProxy {
    private static final Logger logger = LoggerFactory.getLogger(Scanner.class);

    protected final BlockingQueue<Document> queue;

	private final ArrayDeque<String> includeGlobs = new ArrayDeque<>();
	private final ArrayDeque<String> excludeGlobs = new ArrayDeque<>();

	private final DocumentFactory factory;
	private final SealableLatch latch;
	private final Notifiable notifiable;

	private long queued = 0;

	private boolean ignoreHiddenFiles = false;
	private boolean ignoreSystemFiles = true;
	private Options<String> options = new Options<>();

	/**
	 * @see Scanner(BlockingQueue, SealableLatch, Notifiable)
	 */
	public Scanner(final DocumentFactory factory, final BlockingQueue<Document> queue) {
		this(factory, queue, null, null);
	}

	/**
	 * @see Scanner(BlockingQueue, SealableLatch, Notifiable)
	 */
	public Scanner(final DocumentFactory factory, final BlockingQueue<Document> queue, final SealableLatch latch) {
		this(factory, queue, latch, null);
	}

	/**
	 * Creates a {@code Scanner} that sends all results straight to the underlying {@link BlockingQueue<Document>} on a
	 * single thread.
	 *
	 * @param queue results from the scanner will be put on this queue
	 * @param latch signalled when a document is queued
	 * @param notifiable receives notifications when new file documents are queued
	 */
	public Scanner(final DocumentFactory factory, final BlockingQueue<Document> queue, final SealableLatch latch, final
	Notifiable notifiable) {
		super(Executors.newSingleThreadExecutor());
		this.factory = factory;
		this.queue = queue;
		this.notifiable = notifiable;
		this.latch = latch;
	}

	/**
	 * Configure the scanner with the given options.
	 *
	 * @param options options for configuring the scanner
	 * @return the scanner
	 */
	public Scanner configure(final Options<String> options) {
		options.get("includeOSFiles").parse().asBoolean().ifPresent(this::ignoreSystemFiles);
		options.get("includeHiddenFiles").parse().asBoolean().ifPresent(this::ignoreHiddenFiles);
		options.get("includePattern").values().forEach(this::include);
		options.get("excludePattern").values().forEach(this::exclude);
		this.options = options;
		return this;
	}

	/**
	 * Add a glob pattern for including files. Files not matching the pattern will be ignored.
	 *
	 * @param pattern the glob pattern
	 */
	public void include(final String pattern) {
		includeGlobs.add("glob:" + pattern);
	}

	/**
	 * Add a glob pattern for excluding files and directories.
	 *
	 * @param pattern the glob pattern
	 */
	public void exclude(final String pattern) {
		excludeGlobs.add("glob:" + pattern);
	}

	/**
	 * Set whether symlinks should be followed.
	 *
	 * @param followLinks whether to follow symlinks
	 */
	public void followSymLinks(final boolean followLinks) {
		options.add(new org.icij.task.Option<>(FOLLOW_SYMLINKS, StringOptionParser::new).
                update(Boolean.toString(followLinks)));
	}

	/**
	 * Check whether symlinks will be followed.
	 *
	 * @return whether symlinks will be followed
	 */
	public boolean followSymLinks() {
		return options.ifPresent(FOLLOW_SYMLINKS, o -> o.parse().asBoolean()).orElse(false);
	}

	/**
	 * Set whether hidden files should be ignored.
	 *
	 * File names starting with a dot will always be ignored if set to {@literal true}, but DOS hidden files will
	 * only be ignored if the filesystem supports the DOS hidden fileattribute.
	 *
	 * @param ignoreHiddenFiles whether to ignore hidden files
	 */
	public void ignoreHiddenFiles(final boolean ignoreHiddenFiles) {
		this.ignoreHiddenFiles = ignoreHiddenFiles;
	}

	/**
	 * Check whether hidden files will be ignored.
	 *
	 * @return whether hidden files will be ignored
	 */
	public boolean ignoreHiddenFiles() {
		return ignoreHiddenFiles;
	}

	/**
	 * Set whether system files should be ignored.
	 *
	 * @param ignoreSystemFiles whether to ignore system files
	 */
	public void ignoreSystemFiles(final boolean ignoreSystemFiles) {
		this.ignoreSystemFiles = ignoreSystemFiles;
	}

	/**
	 * Check whether system files will be ignored.
	 *
	 * @return whether system files are ignore
	 */
	public boolean ignoreSystemFiles() {
		return ignoreSystemFiles;
	}

	/**
	 * Set the maximum depth to recurse when scanning.
	 *
	 * @param maxDepth maximum depth
	 */
	public void setMaxDepth(final int maxDepth) {
        options.add(new org.icij.task.Option<>(MAX_DEPTH, StringOptionParser::new).
                        update(Integer.toString(maxDepth)));
	}

	/**
	 * Get the currently set maximum depth to recurse when scanning.
	 *
	 * @return maximum depth
	 */
	public int getMaxDepth() {
		return options.ifPresent(MAX_DEPTH, o -> o.parse().asInteger()).orElse(Integer.MAX_VALUE);
	}

	/**
	 * Get the latch.
	 *
	 * @return The latch or null if none is set.
	 */
	public SealableLatch getLatch() {
		return latch;
	}

	/**
	 * @return The total number of queued documents over the lifetime of this scanner.
	 */
	public long queued() {
		return queued;
	}

	/**
	 * Queue a scanning job.
	 *
	 * Jobs are put in an unbounded queue and executed in serial, in a separate thread.
	 * This method doesn't block. Call {@link #awaitTermination(long, TimeUnit)} to block.
	 *
	 * @param path the path to scan
	 * @return A {@link Future} that can be used to wait on the result or cancel.
	 */
	public Future<Path> scan(final Path path) {
		return executor.submit(createScannerVisitor(path));
	}

	public ScannerVisitor createScannerVisitor(Path path) {
		final FileSystem fileSystem = path.getFileSystem();
		final ScannerVisitor visitor = new ScannerVisitor(path, queue, factory, options).
				withMonitor(notifiable).withLatch(latch);

		// In order to make hidden-file-ignoring logic more predictable, always ignore file names starting with a
		// dot, but only ignore DOS hidden files if the file system supports that attribute.
		if (ignoreHiddenFiles) {
			visitor.exclude(new PosixHiddenFileMatcher());
			if (fileSystem.supportedFileAttributeViews().contains("dos")) {
				visitor.exclude(new DosHiddenFileMatcher());
			}
		}

		if (ignoreSystemFiles) {
			visitor.exclude(new SystemFileMatcher());
		}

		for (String excludeGlob : excludeGlobs) {
			visitor.exclude(fileSystem.getPathMatcher(excludeGlob));
		}

		for (String includeGlob : includeGlobs) {
			visitor.include(fileSystem.getPathMatcher(includeGlob));
		}

		logger.info(String.format("Queuing scan of: \"%s\".", path));
		return visitor;
	}

	/**
	 * Submit all of the given paths to the scanner for execution, returning a list of {@link Future} objects
	 * representing those tasks.
	 *
	 * @see #scan(Path)
	 * @return a {@link Future} for each path scanned
	 */
	public List<Future<Path>> scan(final Path[] paths) {
		final List<Future<Path>> futures = new ArrayList<>();

		for (Path path : paths) futures.add(scan(path));
		return futures;
	}

	/**
	 * @see #scan(Path[])
	 */
	public List<Future<Path>> scan(final String[] paths) {
		final Path[] _paths = new Path[paths.length];

		for (int i = 0; i < paths.length; i++) _paths[i] = Paths.get(paths[i]);

		return scan(_paths);
	}

	/**
	 * @see Scanner#scan(Path)
	 */
	public Future<Path> scan(final String path) {
		return scan(Paths.get(path));
	}
}
