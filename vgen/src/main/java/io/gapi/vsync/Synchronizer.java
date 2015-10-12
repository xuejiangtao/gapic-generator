package io.gapi.vsync;

import com.google.common.base.Throwables;
import com.google.common.io.CharStreams;

import io.gapi.fx.model.Diag;
import io.gapi.fx.model.SimpleLocation;
import io.gapi.fx.tools.ToolOptions;
import io.gapi.fx.tools.ToolOptions.Option;

import autovalue.shaded.com.google.common.common.base.Preconditions;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Simple implementation of a synchronizer based on kdiff3.
 */
public class Synchronizer extends SimpleFileVisitor<Path> {

  public static final Option<String> SOURCE_PATH = ToolOptions.createOption(
      String.class,
      "source_path",
      "The path to the folder for synchronized code.",
      "");

  public static final Option<String> GENERATED_PATH = ToolOptions.createOption(
      String.class,
      "generated_path",
      "The path to the folder for generated code.",
      "");

  public static final Option<String> BASELINE_PATH = ToolOptions.createOption(
      String.class,
      "baseline_path",
      "The path to the folder for basline of generated code.",
      "");

  public static final Option<String> KDIFF3 = ToolOptions.createOption(
      String.class,
      "kdiff3",
      "Path to the kdiff3 tool.",
      "/usr/bin/kdiff3");

  public static final Option<Boolean> AUTO_MERGE = ToolOptions.createOption(
      Boolean.class,
      "auto",
      "Whether to use the --auto option of kdiff3, such that no gui is launched if conflicts "
      + "can be resolved.",
      true);

  public static final Option<Boolean> AUTO_RESOLUTION = ToolOptions.createOption(
      Boolean.class,
      "qall",
      "If false, use the --qall option of kdiff3 which turns off smart conflict resolution.",
      true);

  public static final Option<Boolean> IGNORE_BASE = ToolOptions.createOption(
      Boolean.class,
      "ignore_base",
      "If true, the baseline will be ignored and two-way merge will be used.",
      true);


  private final ToolOptions options;
  private final List<Diag> diagnosis = new ArrayList<>();

  public Synchronizer(ToolOptions options) {
    this.options = options;
  }

  public Collection<Diag> run() {
    require(SOURCE_PATH);
    require(GENERATED_PATH);
    require(BASELINE_PATH);
    if (!diagnosis.isEmpty()) {
      return diagnosis;
    }

    try {
      Files.walkFileTree(Paths.get(options.get(GENERATED_PATH)), this);
    } catch (IOException e) {
      error("I/O error: %s (%s): %s", e.getMessage(), e.getClass().getSimpleName(),
          Throwables.getStackTraceAsString(e));
    }
    return diagnosis;
  }

  private void require(Option<String> option) {
    if (options.get(option).isEmpty()) {
      error("Missing value for option '%s'", option.name());
    }
  }

  private void error(String message, Object...args) {
    diagnosis.add(Diag.error(SimpleLocation.TOPLEVEL, message, args));
  }

  @Override
  public FileVisitResult visitFile(Path generatedPath, BasicFileAttributes attrs)
      throws IOException {
    // Compute the paths to equivalent files in source and baseline.
    String relativePath = generatedPath.toString();
    Preconditions.checkState(relativePath.startsWith(options.get(GENERATED_PATH) + "/"));
    relativePath = relativePath.substring(options.get(GENERATED_PATH).length() + 1);
    Path sourcePath = Paths.get(options.get(SOURCE_PATH), relativePath);
    Path baselinePath = Paths.get(options.get(BASELINE_PATH), relativePath);

    Files.createDirectories(sourcePath.getParent());
    if (!Files.exists(sourcePath)) {
      // If the source does not yet exist, create it from generated.
      Files.copy(generatedPath, sourcePath);
    } else {
      // Perform 3-way or 2-way merge
      List<String> cmdArgs = new ArrayList<>();
      cmdArgs.add(options.get(KDIFF3));
      cmdArgs.add("--merge");
      if (options.get(AUTO_MERGE)) {
        cmdArgs.add("--auto");
      }
      if (!options.get(AUTO_RESOLUTION)) {
        cmdArgs.add("--qall");
      }
      cmdArgs.add("--output");
      cmdArgs.add(sourcePath.toString());
      if (!options.get(IGNORE_BASE) && Files.exists(baselinePath)) {
        cmdArgs.add(baselinePath.toString());
        cmdArgs.add("--fname");
        cmdArgs.add("BASE " + relativePath);
      }
      cmdArgs.add(generatedPath.toString());
      cmdArgs.add("--fname");
      cmdArgs.add("GENERATED " + relativePath);
      cmdArgs.add(sourcePath.toString());
      cmdArgs.add("--fname");
      cmdArgs.add("EDITED " + relativePath);
      Process process = new ProcessBuilder()
          .command(cmdArgs)
          .redirectErrorStream(true)
          .start();
      InputStreamReader stdout = new InputStreamReader(process.getInputStream());
      try {
        int resultCode = process.waitFor();
        if (resultCode != 0) {
          error("unresolved merge conflict for '%s', aborting. Output:%n%s",
              relativePath,
              CharStreams.toString(stdout));
          return FileVisitResult.TERMINATE;
        }
      } catch (InterruptedException e) {
        error("interrupted during merge conflict resolution for '%s', aborting. Output:%n%s",
            relativePath,
            CharStreams.toString(stdout));
        return FileVisitResult.TERMINATE;
      }
    }

    // Update the baseline.
    Files.createDirectories(baselinePath.getParent());
    Files.copy(generatedPath, baselinePath, StandardCopyOption.REPLACE_EXISTING);
    return FileVisitResult.CONTINUE;
  }

  private static String quote(String s) {
    return "\"" + s + "\"";
  }
}