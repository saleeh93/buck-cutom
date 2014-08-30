/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cli;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventListener;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.listener.AbstractConsoleEventBusListener;
import com.facebook.buck.event.listener.ChromeTraceBuildListener;
import com.facebook.buck.event.listener.JavaUtilsLoggingBuildListener;
import com.facebook.buck.event.listener.LoggingBuildListener;
import com.facebook.buck.event.listener.SimpleConsoleEventBusListener;
import com.facebook.buck.event.listener.SuperConsoleEventBusListener;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.log.CommandThreadAssociation;
import com.facebook.buck.log.LogConfig;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildId;
import com.facebook.buck.parser.Parser;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.CachingBuildEngine;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.Repository;
import com.facebook.buck.rules.RepositoryFactory;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKey.Builder;
import com.facebook.buck.rules.RuleKeyBuilderFactory;
import com.facebook.buck.timing.Clock;
import com.facebook.buck.timing.DefaultClock;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.DefaultFileHashCache;
import com.facebook.buck.util.FileHashCache;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.InterruptionFailedException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProjectFilesystem;
import com.facebook.buck.util.ProjectFilesystemWatcher;
import com.facebook.buck.util.Verbosity;
import com.facebook.buck.util.WatchServiceWatcher;
import com.facebook.buck.util.WatchmanWatcher;
import com.facebook.buck.util.WatchmanWatcherException;
import com.facebook.buck.util.concurrent.TimeSpan;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.environment.Platform;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.eventbus.EventBus;
import com.google.common.reflect.ClassPath;
import com.google.common.util.concurrent.AbstractScheduledService;
import com.google.common.util.concurrent.ServiceManager;
import com.martiansoftware.nailgun.NGClientListener;
import com.martiansoftware.nailgun.NGContext;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

public final class Main {

  /**
   * Trying again won't help.
   */
  public static final int FAIL_EXIT_CODE = 1;

  /**
   * Trying again later might work.
   */
  public static final int BUSY_EXIT_CODE = 2;

  private static final String BUCK_VERSION_UID_KEY = "buck.version_uid";
  private static final String BUCK_VERSION_UID = System.getProperty(BUCK_VERSION_UID_KEY, "N/A");

  private static final String BUCKD_COLOR_DEFAULT_ENV_VAR = "BUCKD_COLOR_DEFAULT";

  private static final int ARTIFACT_CACHE_TIMEOUT_IN_SECONDS = 15;

  private static final TimeSpan DAEMON_SLAYER_TIMEOUT = new TimeSpan(2, TimeUnit.HOURS);

  private static final TimeSpan SUPER_CONSOLE_REFRESH_RATE =
      new TimeSpan(100, TimeUnit.MILLISECONDS);

  /**
   * Path to a directory of static content that should be served by the {@link WebServer}.
   */
  private static final String STATIC_CONTENT_DIRECTORY = System.getProperty(
      "buck.path_to_static_content", "webserver/static");

  private final PrintStream stdOut;
  private final PrintStream stdErr;
  private final Optional<BuckEventListener> externalEventsListener;

  private static final Semaphore commandSemaphore = new Semaphore(1);

  private final Platform platform;

  // It's important to re-use this object for perf:
  // http://wiki.fasterxml.com/JacksonBestPracticesPerformance
  private final ObjectMapper objectMapper;

  // This is a hack to work around a perf issue where generated Xcode IDE files
  // trip WatchmanWatcher, causing buck project to take a long time to run.
  private static final ImmutableSet<String> DEFAULT_IGNORE_GLOBS =
      ImmutableSet.of("*.pbxproj", "*.xcscheme", "*.xcworkspacedata");

  private static final Logger LOG = Logger.get(Main.class);

  /**
   * Daemon used to monitor the file system and cache build rules between Main() method
   * invocations is static so that it can outlive Main() objects and survive for the lifetime
   * of the potentially long running Buck process.
   */
  private static final class Daemon implements Closeable {

    private final Repository repository;
    private final Parser parser;
    private final DefaultFileHashCache hashCache;
    private final EventBus fileEventBus;
    private final ProjectFilesystemWatcher filesystemWatcher;
    private final Optional<WebServer> webServer;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public Daemon(
        Repository repository,
        Clock clock,
        ObjectMapper objectMapper) throws IOException {
      this.repository = repository;
      this.clock = Preconditions.checkNotNull(clock);
      this.objectMapper = Preconditions.checkNotNull(objectMapper);
      this.hashCache = new DefaultFileHashCache(repository.getFilesystem());
      this.parser = new Parser(
          repository,
          repository.getBuckConfig().getPythonInterpreter(),
          repository.getBuckConfig().getTempFilePatterns(),
          createRuleKeyBuilderFactory(hashCache));

      this.fileEventBus = new EventBus("file-change-events");
      this.filesystemWatcher = createWatcher(repository.getFilesystem());
      fileEventBus.register(parser);
      fileEventBus.register(hashCache);
      webServer = createWebServer(repository.getBuckConfig(), repository.getFilesystem());
      JavaUtilsLoggingBuildListener.ensureLogFileIsWritten(repository.getFilesystem());
    }

    private ProjectFilesystemWatcher createWatcher(ProjectFilesystem projectFilesystem)
        throws IOException {
      if (System.getProperty("buck.buckd_watcher", "WatchService").equals("Watchman")) {
        LOG.debug("Using watchman to watch for file changes.");
        return new WatchmanWatcher(
            projectFilesystem,
            fileEventBus,
            clock,
            objectMapper,
            repository.getBuckConfig().getIgnorePaths(),
            DEFAULT_IGNORE_GLOBS
        );
      }
      LOG.debug("Using java.nio.file.WatchService to watch for file changes.");
      return new WatchServiceWatcher(
          projectFilesystem,
          fileEventBus,
          FileSystems.getDefault().newWatchService());
    }

    private Optional<WebServer> createWebServer(
        BuckConfig config,
        ProjectFilesystem projectFilesystem) {
      // Enable the web httpserver if it is given by command line parameter or specified in
      // .buckconfig. The presence of a port number is sufficient.
      Optional<String> serverPort =
          Optional.fromNullable(System.getProperty("buck.httpserver.port"));
      if (!serverPort.isPresent()) {
        serverPort = config.getValue("httpserver", "port");
      }
      Optional<WebServer> webServer;
      if (serverPort.isPresent() && !serverPort.get().isEmpty()) {
        String rawPort = serverPort.get();
        try {
          int port = Integer.parseInt(rawPort, 10);
          LOG.debug("Starting up web server on port %d.", port);
          webServer = Optional.of(new WebServer(port, projectFilesystem, STATIC_CONTENT_DIRECTORY));
        } catch (NumberFormatException e) {
          LOG.error("Could not parse port for httpserver: %s.", rawPort);
          webServer = Optional.absent();
        }
      } else {
        webServer = Optional.absent();
      }
      return webServer;
    }

    public Optional<WebServer> getWebServer() {
      return webServer;
    }

    private Parser getParser() {
      return parser;
    }

    private void watchClient(final NGContext context) {
      context.addClientListener(new NGClientListener() {
        @Override
        public void clientDisconnected() throws InterruptedException {

          // Synchronize on parser object so that the main command processing thread is not
          // interrupted mid way through a Parser cache update by the Thread.interrupt() call
          // triggered by System.exit(). The Parser cache will be reused by subsequent commands
          // so needs to be left in a consistent state even if the current command is interrupted
          // due to a client disconnection.
          synchronized (parser) {
            LOG.info("Client disconnected.");
            // Client should no longer be connected, but printing helps detect false disconnections.
            context.err.println("Client disconnected.");
            throw new InterruptedException("Client disconnected.");
          }
        }
      });
    }

    private void watchFileSystem(
        CommandEvent commandEvent,
        BuckEventBus eventBus) throws IOException, InterruptedException {

      // Synchronize on parser object so that all outstanding watch events are processed
      // as a single, atomic Parser cache update and are not interleaved with Parser cache
      // invalidations triggered by requests to parse build files or interrupted by client
      // disconnections.
      synchronized (parser) {
        parser.recordParseStartTime(eventBus);
        fileEventBus.post(commandEvent);
        filesystemWatcher.postEvents();
      }
    }

    /** @return true if the web server was started successfully. */
    private boolean initWebServer() {
      if (webServer.isPresent()) {
        try {
          webServer.get().start();
          return true;
        } catch (WebServer.WebServerException e) {
          LOG.error(e);
        }
      }
      return false;
    }

    @Override
    public void close() throws IOException {
      filesystemWatcher.close();
      shutdownWebServer();
    }

    private void shutdownWebServer() {
      if (webServer.isPresent()) {
        try {
          webServer.get().stop();
        } catch (WebServer.WebServerException e) {
          LOG.error(e);
        }
      }
    }
  }

  @Nullable private static volatile Daemon daemon;

  /**
   * Get or create Daemon.
   */
  @VisibleForTesting
  static Daemon getDaemon(
      Repository repository,
      Clock clock,
      ObjectMapper objectMapper) throws IOException {
    if (daemon == null) {
      LOG.debug("Starting up daemon for project root [%s]",
          repository.getFilesystem().getRootPath());
      daemon = new Daemon(repository, clock, objectMapper);
    } else {
      // Buck daemons cache build files within a single project root, changing to a different
      // project root is not supported and will likely result in incorrect builds. The buck and
      // buckd scripts attempt to enforce this, so a change in project root is an error that
      // should be reported rather than silently worked around by invalidating the cache and
      // creating a new daemon object.
      Path parserRoot = daemon.getParser().getProjectRoot();
      if (!repository.getFilesystem().getRootPath().equals(parserRoot)) {
        throw new HumanReadableException(String.format("Unsupported root path change from %s to %s",
            repository.getFilesystem().getRootPath(), parserRoot));
      }

      // If Buck config or the AndroidDirectoryResolver has changed, invalidate the cache and
      // create a new daemon.
      if (!daemon.repository.equals(repository)) {
        LOG.info("Shutting down and restarting daemon on config or directory resolver change.");
        daemon.close();
        daemon = new Daemon(repository, clock, objectMapper);
      }
    }
    return daemon;
  }

  @VisibleForTesting
  @SuppressWarnings("PMD.EmptyCatchBlock")
  static void resetDaemon() {
    if (daemon != null) {
      try {
        LOG.info("Closing daemon on reset request.");
        daemon.close();
      } catch (IOException e) {
        // Swallow exceptions while closing daemon.
      }
    }
    daemon = null;
  }

  @VisibleForTesting
  static void registerFileWatcher(Object watcher) {
    Preconditions.checkNotNull(daemon);
    daemon.fileEventBus.register(watcher);
  }

  @VisibleForTesting
  static void watchFilesystem() throws IOException, InterruptedException {
    Preconditions.checkNotNull(daemon);
    daemon.filesystemWatcher.postEvents();
  }

  @VisibleForTesting
  public Main(PrintStream stdOut, PrintStream stdErr) {
    this(stdOut, stdErr, Optional.<BuckEventListener>absent());
  }

  @VisibleForTesting
  public Main(
      PrintStream stdOut,
      PrintStream stdErr,
      Optional<BuckEventListener> externalEventsListener) {
    this.stdOut = Preconditions.checkNotNull(stdOut);
    this.stdErr = Preconditions.checkNotNull(stdErr);
    this.platform = Platform.detect();
    this.objectMapper = new ObjectMapper();
    this.externalEventsListener = externalEventsListener;
  }

  /** Prints the usage message to standard error. */
  @VisibleForTesting
  int usage() {
    stdErr.println("buck build tool");

    stdErr.println("usage:");
    stdErr.println("  buck [options]");
    stdErr.println("  buck command --help");
    stdErr.println("  buck command [command-options]");
    stdErr.println("available commands:");

    int lengthOfLongestCommand = 0;
    for (Command command : Command.values()) {
      String name = command.name();
      if (name.length() > lengthOfLongestCommand) {
        lengthOfLongestCommand = name.length();
      }
    }

    for (Command command : Command.values()) {
      String name = command.name().toLowerCase();
      stdErr.printf("  %s%s  %s\n",
          name,
          Strings.repeat(" ", lengthOfLongestCommand - name.length()),
          command.getShortDescription());
    }

    stdErr.println("options:");
    new GenericBuckOptions(stdOut, stdErr).printUsage();
    return 1;
  }

  /**
   *
   * @param buildId an identifier for this command execution.
   * @param context an optional NGContext that is present if running inside a Nailgun server.
   * @param args command line arguments
   * @return an exit code or {@code null} if this is a process that should not exit
   */
  public int runMainWithExitCode(
      BuildId buildId,
      File projectRoot,
      Optional<NGContext> context,
      String... args)
      throws IOException, InterruptedException {

    // Find and execute command.
    int exitCode;
    Command.ParseResult command = parseCommandIfPresent(args);
    if (command.getCommand().isPresent()) {
      return executeCommand(buildId, projectRoot, command, context, args);
    } else {
      exitCode = new GenericBuckOptions(stdOut, stdErr).execute(args);
      if (exitCode == GenericBuckOptions.SHOW_MAIN_HELP_SCREEN_EXIT_CODE) {
        return usage();
      } else {
        return exitCode;
      }
    }
  }

  private Command.ParseResult parseCommandIfPresent(String... args) {
    if (args.length == 0) {
      return new Command.ParseResult(Optional.<Command>absent(), Optional.<String>absent());
    }
    return Command.parseCommandName(args[0]);
  }

  /**
   * @param context an optional NGContext that is present if running inside a Nailgun server.
   * @param args command line arguments
   * @return an exit code or {@code null} if this is a process that should not exit
   */
  @SuppressWarnings({"PMD.EmptyCatchBlock", "PMD.PrematureDeclaration"})
  public int executeCommand(
      BuildId buildId,
      File projectRoot,
      Command.ParseResult commandParseResult,
      Optional<NGContext> context,
      String... args) throws IOException, InterruptedException {

    // Get the client environment, either from this process or from the Nailgun context.
    ImmutableMap<String, String> clientEnvironment = getClientEnvironment(context);

    Verbosity verbosity = VerbosityParser.parse(args);
    Optional<String> color;
    final boolean isDaemon = context.isPresent();
    if (isDaemon && (context.get().getEnv() != null)) {
      String colorString = context.get().getEnv().getProperty(BUCKD_COLOR_DEFAULT_ENV_VAR);
      color = Optional.fromNullable(colorString);
    } else {
      color = Optional.absent();
    }
    // We need a BuckConfig to create a Console, but we get BuckConfig from Repository, and we need
    // a Console to create a Repository. To break this bootstrapping loop, create a temporary
    // BuckConfig.
    // TODO(jacko): We probably shouldn't rely on BuckConfig to instantiate Console.
    BuckConfig bootstrapConfig = BuckConfig.createDefaultBuckConfig(
        new ProjectFilesystem(projectRoot),
        platform,
        clientEnvironment);
    final Console console = new Console(
        verbosity,
        stdOut,
        stdErr,
        bootstrapConfig.createAnsi(color));

    Path canonicalRootPath = projectRoot.toPath().toRealPath();
    RepositoryFactory repositoryFactory =
        new RepositoryFactory(clientEnvironment, platform, console, canonicalRootPath);

    Repository rootRepository = repositoryFactory.getRepositoryByAbsolutePath(canonicalRootPath);

    if (commandParseResult.getErrorText().isPresent()) {
      console.getStdErr().println(commandParseResult.getErrorText().get());
    }

    int exitCode;
    ImmutableList<BuckEventListener> eventListeners;
    Clock clock = new DefaultClock();
    ProcessExecutor processExecutor = new ProcessExecutor(console);
    ExecutionEnvironment executionEnvironment = new DefaultExecutionEnvironment(
        processExecutor,
        clientEnvironment,
        // TODO(user): Thread through properties from client environment.
        System.getProperties());

    // No more early outs: if this command is not read only, acquire the command semaphore to
    // become the only executing read/write command.
    // This must happen immediately before the try block to ensure that the semaphore is released.
    boolean commandSemaphoreAcquired = false;
    if (!commandParseResult.getCommand().get().isReadOnly()) {
      commandSemaphoreAcquired = commandSemaphore.tryAcquire();
      if (!commandSemaphoreAcquired) {
        return BUSY_EXIT_CODE;
      }
    }

    @Nullable ArtifactCacheFactory artifactCacheFactory = null;

    // The order of resources in the try-with-resources block is important: the BuckEventBus must
    // be the last resource, so that it is closed first and can deliver its queued events to the
    // other resources before they are closed.
    try (ConsoleLogLevelOverrider consoleLogLevelOverrider =
             new ConsoleLogLevelOverrider(buildId.toString(), verbosity);
         ConsoleHandlerRedirector consoleHandlerRedirector =
             new ConsoleHandlerRedirector(
                 buildId.toString(),
                 console.getStdErr(),
                 Optional.<OutputStream>of(stdErr));
         AbstractConsoleEventBusListener consoleListener =
             createConsoleEventListener(
                 clock,
                 console,
                 verbosity,
                 executionEnvironment,
                 rootRepository.getBuckConfig());
         BuckEventBus buildEventBus = new BuckEventBus(clock, buildId)) {

      // The ArtifactCache is constructed lazily so that we do not try to connect to Cassandra when
      // running commands such as `buck clean`.
      artifactCacheFactory = new LoggingArtifactCacheFactory(executionEnvironment, buildEventBus);

      Optional<WebServer> webServer = getWebServerIfDaemon(context, rootRepository, clock);
      eventListeners = addEventListeners(buildEventBus,
          rootRepository.getFilesystem(),
          rootRepository.getBuckConfig(),
          webServer,
          console,
          consoleListener,
          rootRepository.getKnownBuildRuleTypes(),
          clientEnvironment);

      ImmutableList<String> remainingArgs = ImmutableList.copyOf(
          Arrays.copyOfRange(args, 1, args.length));

      Command executingCommand = commandParseResult.getCommand().get();
      String commandName = executingCommand.name().toLowerCase();

      CommandEvent commandEvent = CommandEvent.started(commandName, remainingArgs, isDaemon);
      buildEventBus.post(commandEvent);

      // Create or get Parser and invalidate cached command parameters.
      Parser parser = null;

      if (isDaemon) {
        try {
          parser = getParserFromDaemon(
              context,
              rootRepository,
              commandEvent,
              buildEventBus,
              clock);
        } catch (WatchmanWatcherException | IOException e) {
          buildEventBus.post(ConsoleEvent.warning(
                  "Watchman threw an exception while parsing file changes.\n%s",
                  e.getMessage()));
        }
      }

      if (parser == null) {
        parser = new Parser(
            rootRepository,
            rootRepository.getBuckConfig().getPythonInterpreter(),
            rootRepository.getBuckConfig().getTempFilePatterns(),
            createRuleKeyBuilderFactory(new DefaultFileHashCache(rootRepository.getFilesystem())));
      }
      JavaUtilsLoggingBuildListener.ensureLogFileIsWritten(rootRepository.getFilesystem());

      CachingBuildEngine buildEngine = new CachingBuildEngine();
      exitCode = executingCommand.execute(remainingArgs,
          rootRepository.getBuckConfig(),
          new CommandRunnerParams(
              console,
              rootRepository,
              rootRepository.androidDirectoryResolver,
              buildEngine,
              artifactCacheFactory,
              buildEventBus,
              parser,
              platform,
              clientEnvironment,
              rootRepository.getBuckConfig().createDefaultJavaPackageFinder(),
              objectMapper));

      // If the Daemon is running and serving web traffic, print the URL to the Chrome Trace.
      if (webServer.isPresent()) {
        int port = webServer.get().getPort();
        buildEventBus.post(ConsoleEvent.info(
            "See trace at http://localhost:%s/trace/%s", port, buildId));
      }

      buildEventBus.post(CommandEvent.finished(commandName, remainingArgs, isDaemon, exitCode));
    } catch (Throwable t) {
      LOG.debug(t, "Failing build on exception.");
      closeCreatedArtifactCaches(artifactCacheFactory); // Close cache before exit on exception.
      throw t;
    } finally {
      if (commandSemaphoreAcquired) {
        commandSemaphore.release(); // Allow another command to execute while outputting traces.
      }
    }
    if (isDaemon && !rootRepository.getBuckConfig().getFlushEventsBeforeExit()) {
      context.get().in.close(); // Avoid client exit triggering client disconnection handling.
      context.get().exit(exitCode); // Allow nailgun client to exit while outputting traces.
    }
    closeCreatedArtifactCaches(artifactCacheFactory); // Wait for cache close after client exit.
    for (BuckEventListener eventListener : eventListeners) {
      try {
        eventListener.outputTrace(buildId);
      } catch (RuntimeException e) {
        System.err.println("Skipping over non-fatal error");
        e.printStackTrace();
      }
    }
    return exitCode;
  }

  /**
   * @return the client environment, which is either the process environment or the
   * environment sent to the daemon by the Nailgun client. This method should always be used
   * in preference to System.getenv() and should be the only call to System.getenv() within the
   * Buck codebase to ensure that the use of the Buck daemon is transparent.
   */
  @SuppressWarnings({"unchecked", "rawtypes"}) // Safe as Property is a Map<String, String>.
  private ImmutableMap<String, String> getClientEnvironment(Optional<NGContext> context) {
    ImmutableMap<String, String> env;
    if (context.isPresent()) {
      env = ImmutableMap.<String, String>copyOf((Map) context.get().getEnv());
    } else {
      env = ImmutableMap.copyOf(System.getenv());
    }
    return EnvironmentFilter.filteredEnvironment(env);
  }

  private static void closeCreatedArtifactCaches(
      @Nullable ArtifactCacheFactory artifactCacheFactory)
      throws InterruptedException {
    if (null != artifactCacheFactory) {
      artifactCacheFactory.closeCreatedArtifactCaches(ARTIFACT_CACHE_TIMEOUT_IN_SECONDS);
    }
  }

  private Parser getParserFromDaemon(
      Optional<NGContext> context,
      Repository repository,
      CommandEvent commandEvent,
      BuckEventBus eventBus,
      Clock clock) throws IOException, InterruptedException {
    // Wire up daemon to new client and get cached Parser.
    Daemon daemon = getDaemon(repository, clock, objectMapper);
    daemon.watchClient(context.get());
    daemon.watchFileSystem(commandEvent, eventBus);
    daemon.initWebServer();
    return daemon.getParser();
  }

  private Optional<WebServer> getWebServerIfDaemon(
      Optional<NGContext> context,
      Repository repository,
      Clock clock) throws IOException {
    if (context.isPresent()) {
      Daemon daemon = getDaemon(repository, clock, objectMapper);
      return daemon.getWebServer();
    }
    return Optional.absent();
  }

  private void loadListenersFromBuckConfig(
      ImmutableList.Builder<BuckEventListener> eventListeners,
      ProjectFilesystem projectFilesystem,
      BuckConfig config) {
    final ImmutableSet<String> paths = config.getListenerJars();
    if (paths.isEmpty()) {
      return;
    }

    URL[] urlsArray = new URL[paths.size()];
    try {
      int i = 0;
      for (String path : paths) {
        String urlString = "file://" + projectFilesystem.getAbsolutifier().apply(Paths.get(path));
        urlsArray[i] = new URL(urlString);
        i++;
      }
    } catch (MalformedURLException e) {
      throw new HumanReadableException(e.getMessage());
    }

    // This ClassLoader is disconnected to allow searching the JARs (and just the JARs) for classes.
    ClassLoader isolatedClassLoader = URLClassLoader.newInstance(urlsArray, null);

    ImmutableSet<ClassPath.ClassInfo> classInfos;
    try {
      ClassPath classPath = ClassPath.from(isolatedClassLoader);
      classInfos = classPath.getTopLevelClasses();
    } catch (IOException e) {
      throw new HumanReadableException(e.getMessage());
    }

    // This ClassLoader will actually work, because it is joined to the parent ClassLoader.
    URLClassLoader workingClassLoader = URLClassLoader.newInstance(urlsArray);

    for (ClassPath.ClassInfo classInfo : classInfos) {
      String className = classInfo.getName();
      try {
        Class<?> aClass = Class.forName(className, true, workingClassLoader);
        if (BuckEventListener.class.isAssignableFrom(aClass)) {
          BuckEventListener listener = aClass.asSubclass(BuckEventListener.class).newInstance();
          eventListeners.add(listener);
        }
      } catch (ReflectiveOperationException e) {
        throw new HumanReadableException("Error loading event listener class '%s': %s: %s",
            className,
            e.getClass(),
            e.getMessage());
      }
    }
  }

  private ImmutableList<BuckEventListener> addEventListeners(
      BuckEventBus buckEvents,
      ProjectFilesystem projectFilesystem,
      BuckConfig config,
      Optional<WebServer> webServer,
      Console console,
      AbstractConsoleEventBusListener consoleEventBusListener,
      KnownBuildRuleTypes knownBuildRuleTypes,
      ImmutableMap<String, String> environment) {
    ImmutableList.Builder<BuckEventListener> eventListenersBuilder =
        ImmutableList.<BuckEventListener>builder()
            .add(new JavaUtilsLoggingBuildListener())
            .add(new ChromeTraceBuildListener(projectFilesystem, config.getMaxTraces()))
            .add(consoleEventBusListener)
            .add(new LoggingBuildListener());

    if (webServer.isPresent()) {
      eventListenersBuilder.add(webServer.get().createListener());
    }

    loadListenersFromBuckConfig(eventListenersBuilder, projectFilesystem, config);



    eventListenersBuilder.add(MissingSymbolsHandler.createListener(
            projectFilesystem,
            knownBuildRuleTypes.getAllDescriptions(),
            config,
            buckEvents,
            console,
            environment));

    ImmutableList<BuckEventListener> eventListeners = eventListenersBuilder.build();

    for (BuckEventListener eventListener : eventListeners) {
      buckEvents.register(eventListener);
    }

    if (externalEventsListener.isPresent()) {
      buckEvents.register(externalEventsListener.get());
    }

    return eventListeners;
  }

  private AbstractConsoleEventBusListener createConsoleEventListener(
      Clock clock,
      Console console,
      Verbosity verbosity,
      ExecutionEnvironment executionEnvironment,
      BuckConfig config) {
    if (console.getAnsi().isAnsiTerminal() &&
        !verbosity.shouldPrintCommand() &&
        verbosity.shouldPrintStandardInformation()) {
      SuperConsoleEventBusListener superConsole = new SuperConsoleEventBusListener(
          console,
          clock,
          executionEnvironment,
          config.isTreatingAssumptionsAsErrors());
      superConsole.startRenderScheduler(SUPER_CONSOLE_REFRESH_RATE.getDuration(),
          SUPER_CONSOLE_REFRESH_RATE.getUnit());
      return superConsole;
    }
    return new SimpleConsoleEventBusListener(
        console,
        clock,
        config.isTreatingAssumptionsAsErrors());
  }

  /**
   * @param hashCache A cache of file content hashes, used to avoid reading and hashing input files.
   */
  private static RuleKeyBuilderFactory createRuleKeyBuilderFactory(final FileHashCache hashCache) {
    return new RuleKeyBuilderFactory() {
      @Override
      public Builder newInstance(BuildRule buildRule) {
        RuleKey.Builder builder = RuleKey.builder(buildRule, hashCache);
        builder.set("buckVersionUid", BUCK_VERSION_UID);
        return builder;
      }
    };
  }

  @VisibleForTesting
  int tryRunMainWithExitCode(
      BuildId buildId,
      File projectRoot,
      Optional<NGContext> context,
      String... args)
      throws IOException, InterruptedException {
    try {
      if (daemon != null) {
        // Reset logging each time we run a command while daemonized.
        LOG.debug("Rotating log.");
        LogConfig.flushLogs();
        LogConfig.setupLogging();
      }
      LOG.debug("Starting up with args: %s", Arrays.toString(args));
      return runMainWithExitCode(buildId, projectRoot, context, args);
    } catch (HumanReadableException e) {
      Console console = new Console(Verbosity.STANDARD_INFORMATION,
          stdOut,
          stdErr,
          new Ansi(platform, Optional.<String>absent()));
      console.printBuildFailure(e.getHumanReadableErrorMessage());
      return FAIL_EXIT_CODE;
    } catch (InterruptionFailedException e) { // Command could not be interrupted.
      if (context.isPresent()) {
        context.get().getNGServer().shutdown(true); // Exit process to halt command execution.
      }
      return FAIL_EXIT_CODE;
    } finally {
      LOG.debug("Done.");
    }
  }

  private void runMainThenExit(String[] args, Optional<NGContext> context) {
    File projectRoot = new File(".");
    int exitCode = FAIL_EXIT_CODE;
    BuildId buildId = new BuildId();

    // Note that try-with-resources blocks close their resources *before*
    // executing catch or finally blocks. That means we can't use one here,
    // since those blocks may need to log.
    CommandThreadAssociation commandThreadAssociation = null;
    ConsoleHandlerRedirector consoleHandlerRedirector = null;

    try {
      commandThreadAssociation =
        new CommandThreadAssociation(buildId.toString());
      // Redirect console logs to the (possibly remote) stderr stream.
      // We do this for both the daemon and non-daemon case so we can
      // unregister the stream when finished.
      consoleHandlerRedirector = new ConsoleHandlerRedirector(
          buildId.toString(),
          stdErr,
          Optional.<OutputStream>absent() /* originalOutputStream */);
      exitCode = tryRunMainWithExitCode(buildId, projectRoot, context, args);
    } catch (Throwable t) {
      LOG.error(t, "Uncaught exception at top level");
    } finally {
      LogConfig.flushLogs();
      if (commandThreadAssociation != null) {
        commandThreadAssociation.stop();
      }
      if (consoleHandlerRedirector != null) {
        consoleHandlerRedirector.close();
      }
      // Exit explicitly so that non-daemon threads (of which we use many) don't
      // keep the VM alive.
      System.exit(exitCode);
    }
  }

  public static void main(String[] args) {
    new Main(System.out, System.err).runMainThenExit(args, Optional.<NGContext>absent());
  }

  /**
   * When running as a daemon in the NailGun server, {@link #nailMain(NGContext)} is called instead
   * of {@link #main(String[])} so that the given context can be used to listen for client
   * disconnections and interrupt command processing when they occur.
   */
  public static void nailMain(final NGContext context) throws InterruptedException {
    try (DaemonSlayer.ExecuteCommandHandle handle =
            DaemonSlayer.getSlayer(context).executeCommand()) {
      new Main(context.out, context.err).runMainThenExit(context.getArgs(), Optional.of(context));
    }
  }


  private static final class DaemonSlayer extends AbstractScheduledService {
    private final NGContext context;
    private final TimeSpan slayerTimeout;
    private int runCount;
    private int lastRunCount;
    private boolean executingCommand;

    private static final class DaemonSlayerInstance {
      final DaemonSlayer daemonSlayer;

      private DaemonSlayerInstance(DaemonSlayer daemonSlayer) {
        this.daemonSlayer = daemonSlayer;
      }
    }

    @Nullable private static volatile DaemonSlayerInstance daemonSlayerInstance;

    public static DaemonSlayer getSlayer(NGContext context) {
      if (daemonSlayerInstance == null) {
        synchronized (DaemonSlayer.class) {
          if (daemonSlayerInstance == null) {
            DaemonSlayer slayer = new DaemonSlayer(context);
            ServiceManager manager = new ServiceManager(ImmutableList.of(slayer));
            manager.startAsync();
            daemonSlayerInstance = new DaemonSlayerInstance(slayer);
          }
        }
      }
      return daemonSlayerInstance.daemonSlayer;
    }

    private DaemonSlayer(NGContext context) {
      this.context = Preconditions.checkNotNull(context);
      this.runCount = 0;
      this.lastRunCount = 0;
      this.executingCommand = false;
      this.slayerTimeout = DAEMON_SLAYER_TIMEOUT;
    }

    public class ExecuteCommandHandle implements AutoCloseable {
      private ExecuteCommandHandle() {
        synchronized (DaemonSlayer.this) {
          executingCommand = true;
        }
      }

      @Override
      public void close() {
        synchronized (DaemonSlayer.this) {
          runCount++;
          executingCommand = false;
        }
      }
    }

    public ExecuteCommandHandle executeCommand() {
      return new ExecuteCommandHandle();
    }

    @Override
    protected synchronized void runOneIteration() throws Exception {
      if (!executingCommand && runCount == lastRunCount) {
        context.getNGServer().shutdown(/* exitVM */ true);
      } else {
        lastRunCount = runCount;
      }
    }

    @Override
    protected Scheduler scheduler() {
      return Scheduler.newFixedRateSchedule(
          slayerTimeout.getDuration(),
          slayerTimeout.getDuration(),
          slayerTimeout.getUnit());
    }
  }
}
