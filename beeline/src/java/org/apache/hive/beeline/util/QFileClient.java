/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hive.beeline.util;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.util.Shell;
import org.apache.hive.common.util.StreamPrinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hive.beeline.BeeLine;

/**
 * QTestClient.
 *
 */
public class QFileClient {
  private String username;
  private String password;
  private String jdbcUrl;
  private String jdbcDriver;

  private final File hiveRootDirectory;
  private File qFileDirectory;
  private File outputDirectory;
  private File expectedDirectory;
  private final File scratchDirectory;
  private final File warehouseDirectory;
  private final File initScript;
  private final File cleanupScript;

  private File testDataDirectory;
  private File testScriptDirectory;

  private String qFileName;
  private String testname;

  private File qFile;
  private File outputFile;
  private File expectedFile;

  private PrintStream beelineOutputStream;

  private BeeLine beeLine;

  private RegexFilterSet filterSet;

  private boolean hasErrors = false;

  private static final Logger LOG = LoggerFactory
      .getLogger(QFileClient.class.getName());


  public QFileClient(HiveConf hiveConf, String hiveRootDirectory, String qFileDirectory, String outputDirectory,
      String expectedDirectory, String initScript, String cleanupScript) {
    this.hiveRootDirectory = new File(hiveRootDirectory);
    this.qFileDirectory = new File(qFileDirectory);
    this.outputDirectory = new File(outputDirectory);
    this.expectedDirectory = new File(expectedDirectory);
    this.initScript = new File(initScript);
    this.cleanupScript = new File(cleanupScript);
    this.scratchDirectory = new File(hiveConf.getVar(ConfVars.SCRATCHDIR));
    this.warehouseDirectory = new File(hiveConf.getVar(ConfVars.METASTOREWAREHOUSE));
  }


  private class RegexFilterSet {
    private final Map<Pattern, String> regexFilters = new LinkedHashMap<Pattern, String>();

    public RegexFilterSet addFilter(String regex, String replacement) {
      regexFilters.put(Pattern.compile(regex), replacement);
      return this;
    }

    public String filter(String input) {
      for (Pattern pattern : regexFilters.keySet()) {
        input = pattern.matcher(input).replaceAll(regexFilters.get(pattern));
      }
      return input;
    }
  }

  void initFilterSet() {
    // Extract the leading four digits from the unix time value.
    // Use this as a prefix in order to increase the selectivity
    // of the unix time stamp replacement regex.
    String currentTimePrefix = Long.toString(System.currentTimeMillis()).substring(0, 4);

    String userName = System.getProperty("user.name");

    String timePattern = "(Mon|Tue|Wed|Thu|Fri|Sat|Sun) "
        + "(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) "
        + "\\d{2} \\d{2}:\\d{2}:\\d{2} \\w+ 20\\d{2}";
    // Pattern to remove the timestamp and other infrastructural info from the out file
    String logPattern = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2},\\d*\\s+\\S+\\s+\\[" +
                            ".*\\]\\s+\\S+:\\s+";
    String unixTimePattern = "\\D" + currentTimePrefix + "\\d{6}\\D";
    String unixTimeMillisPattern = "\\D" + currentTimePrefix + "\\d{9}\\D";

    String operatorPattern = "\"(CONDITION|COPY|DEPENDENCY_COLLECTION|DDL"
      + "|EXPLAIN|FETCH|FIL|FS|FUNCTION|GBY|HASHTABLEDUMMY|HASTTABLESINK|JOIN"
      + "|LATERALVIEWFORWARD|LIM|LVJ|MAP|MAPJOIN|MAPRED|MAPREDLOCAL|MOVE|OP|RS"
      + "|SCR|SEL|STATS|TS|UDTF|UNION)_\\d+\"";

    filterSet = new RegexFilterSet()
    .addFilter(logPattern,"")
    .addFilter("going to print operations logs\n","")
    .addFilter("printed operations logs\n","")
    .addFilter("Getting log thread is interrupted, since query is done!\n","")
    .addFilter(scratchDirectory.toString() + "[\\w\\-/]+", "!!{hive.exec.scratchdir}!!")
    .addFilter(warehouseDirectory.toString(), "!!{hive.metastore.warehouse.dir}!!")
    .addFilter(expectedDirectory.toString(), "!!{expectedDirectory}!!")
    .addFilter(outputDirectory.toString(), "!!{outputDirectory}!!")
    .addFilter(qFileDirectory.toString(), "!!{qFileDirectory}!!")
    .addFilter(hiveRootDirectory.toString(), "!!{hive.root}!!")
    .addFilter("\\(queryId=[^\\)]*\\)","queryId=(!!{queryId}!!)")
    .addFilter("file:/\\w\\S+", "file:/!!ELIDED!!")
    .addFilter("pfile:/\\w\\S+", "pfile:/!!ELIDED!!")
    .addFilter("hdfs:/\\w\\S+", "hdfs:/!!ELIDED!!")
    .addFilter("last_modified_by=\\w+", "last_modified_by=!!ELIDED!!")
    .addFilter(timePattern, "!!TIMESTAMP!!")
    .addFilter("(\\D)" + currentTimePrefix + "\\d{6}(\\D)", "$1!!UNIXTIME!!$2")
    .addFilter("(\\D)" + currentTimePrefix + "\\d{9}(\\D)", "$1!!UNIXTIMEMILLIS!!$2")
    .addFilter(userName, "!!{user.name}!!")
    .addFilter(operatorPattern, "\"$1_!!ELIDED!!\"")
    .addFilter("Time taken: [0-9\\.]* seconds", "Time taken: !!ELIDED!! seconds")
    ;
  };

  public QFileClient setUsername(String username) {
    this.username = username;
    return this;
  }

  public QFileClient setPassword(String password) {
    this.password = password;
    return this;
  }

  public QFileClient setJdbcUrl(String jdbcUrl) {
    this.jdbcUrl = jdbcUrl;
    return this;
  }

  public QFileClient setJdbcDriver(String jdbcDriver) {
    this.jdbcDriver = jdbcDriver;
    return this;
  }

  public QFileClient setQFileName(String qFileName) {
    this.qFileName = qFileName;
    this.qFile = new File(qFileDirectory, qFileName);
    this.testname = StringUtils.substringBefore(qFileName, ".");
    expectedFile = new File(expectedDirectory, qFileName + ".out");
    outputFile = new File(outputDirectory, qFileName + ".out");
    return this;
  }

  public QFileClient setQFileDirectory(String qFileDirectory) {
    this.qFileDirectory = new File(qFileDirectory);
    return this;
  }

  public QFileClient setOutputDirectory(String outputDirectory) {
    this.outputDirectory = new File(outputDirectory);
    return this;
  }

  public QFileClient setExpectedDirectory(String expectedDirectory) {
    this.expectedDirectory = new File(expectedDirectory);
    return this;
  }

  public QFileClient setTestDataDirectory(String testDataDirectory) {
    this.testDataDirectory = new File(testDataDirectory);
    return this;
  }

  public QFileClient setTestScriptDirectory(String testScriptDirectory) {
    this.testScriptDirectory = new File(testScriptDirectory);
    return this;
  }

  public boolean hasErrors() {
    return hasErrors;
  }

  private void initBeeLine() throws Exception {
    beeLine = new BeeLine();
    beelineOutputStream = new PrintStream(new File(outputDirectory, qFileName + ".beeline"));
    beeLine.setOutputStream(beelineOutputStream);
    beeLine.setErrorStream(beelineOutputStream);
    beeLine.runCommands(new String[] {
        "!set verbose true",
        "!set shownestederrs true",
        "!set showwarnings true",
        "!set showelapsedtime false",
        "!set maxwidth -1",
        "!connect " + jdbcUrl + " " + username + " " + password + " " + jdbcDriver,
    });
  }

  private void setUp() {
    beeLine.runCommands(new String[] {
        "USE default;",
        "SHOW TABLES;",
        "DROP DATABASE IF EXISTS `" + testname + "` CASCADE;",
        "CREATE DATABASE `" + testname + "`;",
        "USE `" + testname + "`;",
        "set test.data.dir=" + testDataDirectory + ";",
        "set test.script.dir=" + testScriptDirectory + ";",
        "!run " + testScriptDirectory + "/" + initScript,
    });
  }

  private void tearDown() {
    beeLine.runCommands(new String[] {
        "!set outputformat table",
        "USE default;",
        "DROP DATABASE IF EXISTS `" + testname + "` CASCADE;",
        "!run " + testScriptDirectory + "/" + cleanupScript,
    });
  }

  private void runQFileTest() throws Exception {
    hasErrors = false;
    beeLine.runCommands(new String[] {
        "!set outputformat csv",
        "!record " + outputDirectory + "/" + qFileName + ".raw",
      });

    if (1 != beeLine.runCommands(new String[] { "!run " + qFileDirectory + "/" + qFileName })) {
      hasErrors = true;
    }
    
    beeLine.runCommands(new String[] { "!record" });
  }


  private void filterResults() throws IOException {
    initFilterSet();
    String rawOutput = FileUtils.readFileToString(new File(outputDirectory, qFileName + ".raw"));
    FileUtils.writeStringToFile(outputFile, filterSet.filter(rawOutput));
  }

  public void cleanup() {
    if (beeLine != null) {
      beeLine.runCommands(new String[] {
          "!quit"
      });
    }
    if (beelineOutputStream != null) {
      beelineOutputStream.close();
    }
    if (hasErrors) {
      String oldFileName = outputDirectory + "/" + qFileName + ".raw";
      String newFileName = oldFileName + ".error";
      try {
        FileUtils.moveFile(new File(oldFileName), new File(newFileName));
      } catch (IOException e) {
        System.out.println("Failed to move '" + oldFileName + "' to '" + newFileName);
      }
    }
  }


  public void run() throws Exception {
    try {
      initBeeLine();
      setUp();
      runQFileTest();
      tearDown();
      filterResults();
    } finally {
      cleanup();
    }
  }

  /**
   * Does the test have a file with expected results to compare the log against.
   * False probably indicates that this is a new test and the caller should
   * copy the log to the expected results directory.
   * @return
   */
  public boolean hasExpectedResults() {
    return expectedFile.exists();
  }

  public boolean compareResults() throws IOException, InterruptedException {
    if (!expectedFile.exists()) {
      LOG.error("Expected results file does not exist: " + expectedFile);
      return false;
    }
    return executeDiff();
  }

  private boolean executeDiff() throws IOException, InterruptedException {
    ArrayList<String> diffCommandArgs = new ArrayList<String>();
    diffCommandArgs.add("diff");

    // Text file comparison
    diffCommandArgs.add("-a");

    if (Shell.WINDOWS) {
      // Ignore changes in the amount of white space
      diffCommandArgs.add("-b");

      // Files created on Windows machines have different line endings
      // than files created on Unix/Linux. Windows uses carriage return and line feed
      // ("\r\n") as a line ending, whereas Unix uses just line feed ("\n").
      // Also StringBuilder.toString(), Stream to String conversions adds extra
      // spaces at the end of the line.
      diffCommandArgs.add("--strip-trailing-cr"); // Strip trailing carriage return on input
      diffCommandArgs.add("-B"); // Ignore changes whose lines are all blank
    }

    // Add files to compare to the arguments list
    diffCommandArgs.add(getQuotedString(expectedFile));
    diffCommandArgs.add(getQuotedString(outputFile));

    System.out.println("Running: " + org.apache.commons.lang.StringUtils.join(diffCommandArgs,
        ' '));
    Process executor = Runtime.getRuntime().exec(diffCommandArgs.toArray(
        new String[diffCommandArgs.size()]));

    StreamPrinter errPrinter = new StreamPrinter(executor.getErrorStream(), null, System.err);
    StreamPrinter outPrinter = new StreamPrinter(executor.getInputStream(), null, System.out);

    outPrinter.start();
    errPrinter.start();

    int result = executor.waitFor();

    outPrinter.join();
    errPrinter.join();

    executor.waitFor();

    return (result == 0);
  }

  private static String getQuotedString(File file) {
    return Shell.WINDOWS ? String.format("\"%s\"", file.getAbsolutePath()) : file.getAbsolutePath();
  }

  public void overwriteResults() {
    try {
      if (expectedFile.exists()) {
        FileUtils.forceDelete(expectedFile);
      }
      FileUtils.copyFileToDirectory(outputFile, expectedDirectory, true);
    } catch (IOException e) {
      LOG.error("Failed to overwrite results!", e);
    }
  }
}