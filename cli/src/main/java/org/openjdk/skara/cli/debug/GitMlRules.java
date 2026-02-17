/*
 * Copyright (c) 2020, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.skara.cli.debug;

import org.openjdk.skara.args.*;
import org.openjdk.skara.cli.Logging;
import org.openjdk.skara.json.*;
import org.openjdk.skara.process.Process;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.*;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.logging.*;
import java.util.regex.Pattern;
import java.util.stream.*;

public class GitMlRules {
    private final static Pattern rfrSubject = Pattern.compile("(?:^Subject: .*?)([78]\\d{6})", Pattern.MULTILINE);
    private final static Pattern rfrSubjectOrIssue = Pattern.compile("(?:(?:^Subject: .*?)|(?:JDK-))([78]\\\\d{6})", Pattern.MULTILINE);
    private final static Logger log = Logger.getLogger("org.openjdk.skara.mlrules");

    private static Pattern reviewPattern = rfrSubject;
    private static int daysOfHistory = 30;
    private static int filterDivider = 5;
    private static Pattern listFilterPattern = Pattern.compile(".*");

    static final List<Flag> flags = List.of(
            Option.shortcut("d")
                  .fullname("days")
                  .describe("DAYS")
                  .helptext("Number of days to look back")
                  .optional(),
            Option.shortcut("f")
                  .fullname("filter")
                  .describe("DIVIDER")
                  .helptext("Divider for filter threshold")
                  .optional(),
            Option.shortcut("o")
                  .fullname("output")
                  .describe("FILE")
                  .helptext("Name of file to write output to")
                  .optional(),
            Option.shortcut("v")
                  .fullname("verify")
                  .describe("CONFIG-FILE")
                  .helptext("Name of json config file to verify against")
                  .optional(),
            Option.shortcut("l")
                  .fullname("lists")
                  .describe("PATTERN")
                  .helptext("Regular expression matching mailing lists to include when verifying (default all known)")
                  .optional(),
            Switch.shortcut("")
                  .fullname("verbose")
                  .helptext("Turn on verbose output")
                  .optional(),
            Switch.shortcut("")
                  .fullname("debug")
                  .helptext("Turn on debugging output")
                  .optional(),
            Switch.shortcut("")
                  .fullname("relaxed")
                  .helptext("Use more relaxed matching when searching for reviews")
                  .optional()
    );

    static final List<Input> inputs = List.of(
            Input.position(0)
                 .describe("repository root or files")
                 .trailing()
                 .required()
    );

    private static String archivePageName(ZonedDateTime month) {
        return DateTimeFormatter.ofPattern("yyyy-MMMM", Locale.US).format(month);
    }

    private static List<ZonedDateTime> monthRange(Duration maxAge) {
        var now = ZonedDateTime.now();
        var start = now.minus(maxAge);
        List<ZonedDateTime> ret = new ArrayList<>();

        while (start.isBefore(now)) {
            ret.add(start);
            var next = start.plus(Duration.ofDays(1));
            while (start.getMonthValue() == next.getMonthValue()) {
                next = next.plus(Duration.ofDays(1));
            }
            start = next;
        }
        return ret;
    }

    private static Set<String> archivePageNames() {
        return monthRange(Duration.of(daysOfHistory, ChronoUnit.DAYS)).stream()
                                                                      .map(GitMlRules::archivePageName)
                                                                      .collect(Collectors.toSet());
    }

    private static Set<String> listSubjects(HttpClient client, String list) {
        var tmpFolder = Path.of("/tmp/mlrules");
        try {
            Files.createDirectories(tmpFolder);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return archivePageNames().parallelStream()
                                 .map(name -> HttpRequest.newBuilder(URI.create("https://mail.openjdk.org/pipermail/" + list + "/" + name + ".txt"))
                                                         .GET().build())
                                 .map(req -> {
                                     try {
                                         var cacheFile = tmpFolder.resolve(req.uri().getPath().replace("/pipermail/", "").replace("/", "-"));
                                         if (Files.exists(cacheFile)) {
                                             log.fine("Reading " + req.uri() + " from cache");
                                             return Files.readString(cacheFile);
                                         }
                                         System.out.println("Fetching " + req.uri().toString());
                                         var body = client.send(req, HttpResponse.BodyHandlers.ofString());
                                         System.out.println("Done fetching " + req.uri().toString());
                                         Files.writeString(cacheFile, body.body());
                                         return body.body();
                                     } catch (IOException | InterruptedException e) {
                                         throw new RuntimeException(e);
                                     }
                                 })
                                 .flatMap(page -> reviewPattern.matcher(page).results().map(mr -> mr.group(1)))
                                 .collect(Collectors.toUnmodifiableSet());
    }

    private static Map<String, Set<String>> listReviewedIssues(String... lists) {
        var ret = new HashMap<String, Set<String>>();
        var client = HttpClient.newBuilder()
                               .connectTimeout(Duration.ofSeconds(30))
                               .build();

        var listIssues = Stream.of(lists).parallel()
                               .collect(Collectors.toMap(list -> list,
                                                         list -> listSubjects(client, list)));

        for (var list : listIssues.entrySet()) {
            for (var issue : list.getValue()) {
                if (!ret.containsKey(issue)) {
                    ret.put(issue, new HashSet<>());
                }
                ret.get(issue).add(list.getKey());
            }
        }
        return ret;
    }

    private static Map<CommitMetadata, Set<String>> issueLists(List<CommitMetadata> commits, Map<String, Set<String>> listReviewedIssues) {
        return commits.stream()
                      .map(commit -> new AbstractMap.SimpleEntry<>(commit, CommitMessageParsers.v1.parse(commit)))
                      .filter(entry -> !entry.getValue().issues().isEmpty())
                      .collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey,
                                                entry -> entry.getValue().issues().stream()
                                                              .flatMap(issue -> listReviewedIssues.getOrDefault(issue.shortId(), Set.of()).stream())
                                                              .collect(Collectors.toSet()),
                                                (a, b) -> a,
                                                LinkedHashMap::new));
    }

    private static class ProgressCounter {
        int progress;
        int progressLen;
    }

    private static Set<String> commitChanges(ReadOnlyRepository repo, CommitMetadata commit) throws IOException {
        var process = Process.capture("git", "diff-tree", "--no-commit-id", "--name-only", "-r", commit.hash().hex())
                             .workdir(repo.root());
        try (var p = process.execute()) {
            var res = p.check();
            return new HashSet<>(res.stdout());
        }
    }

    private static Map<CommitMetadata, Set<String>> commitPaths(ReadOnlyRepository repo, Collection<CommitMetadata> commits) {
        var progress = new ProgressCounter();

        return commits.parallelStream()
                      .map(commit -> {
                          try {
                              var changedFiles = commitChanges(repo, commit);
                              synchronized (progress) {
                                  progress.progress++;
                                  var progressStr = String.format("(%d/%d)...", progress.progress, commits.size());
                                  var removalStr = "\b".repeat(progress.progressLen);
                                  progress.progressLen = progressStr.length();
                                  System.out.print(removalStr + progressStr);
                              }
                              var changedPaths = changedFiles.stream()
                                                             .map(Path::of)
                                                             .filter(Objects::nonNull)
                                                             .map(Path::toString)
                                                             .collect(Collectors.toSet());
                              return new AbstractMap.SimpleEntry<>(commit, changedPaths);
                          } catch (IOException e) {
                              throw new UncheckedIOException(e);
                          }
                      })
                      .collect(Collectors.toMap(AbstractMap.SimpleEntry::getKey,
                                                AbstractMap.SimpleEntry::getValue));
    }

    private static Map<String, List<String>> pathLists(Map<CommitMetadata, Set<String>> commitLists, Map<CommitMetadata, Set<String>> commitPaths) {
        var ret = new HashMap<String, List<String>>();

        for (var commitPath : commitPaths.entrySet()) {
            for (var path : commitPath.getValue()) {
                if (!ret.containsKey(path)) {
                    ret.put(path, new ArrayList<>());
                }
                var lists = commitLists.get(commitPath.getKey());
                if (lists != null) {
                    ret.get(path).addAll(lists);
                }
            }
        }

        return ret;
    }

    private static class TrieEntry {
        String key;
        TrieEntry parent;
        TreeMap<String, TrieEntry> children;
        List<String> values;
    }

    private static TrieEntry mapToTrie(Map<String, List<String>> list) {
        var trie = new TrieEntry();
        trie.key = "";
        trie.parent = null;
        trie.children = new TreeMap<>();

        // Create a prefix tree
        for (var entry : list.entrySet()) {
            var curRoot = trie;
            var pathElements = entry.getKey().split("/");
            for (var c : pathElements) {
                if (curRoot.children.containsKey(c)) {
                    curRoot = curRoot.children.get(c);
                } else {
                    var newRoot = new TrieEntry();
                    newRoot.key = c;
                    newRoot.parent = curRoot;
                    newRoot.children = new TreeMap<>();
                    curRoot.children.put(c, newRoot);
                    curRoot = newRoot;
                }
            }
            curRoot.values = entry.getValue();
        }

        return trie;
    }

    private static Map<String, List<String>> trieToMap(TrieEntry trie, String curPath) {
        var ret = new TreeMap<String, List<String>>();

        for (var child : trie.children.entrySet()) {
            ret.putAll(trieToMap(child.getValue(), curPath + (curPath.length() > 0 ? "/" : "") + child.getKey()));
        }
        if (trie.values != null) {
            ret.put(curPath, trie.values);
        }

        return ret;
    }

    private static Set<String> relevantLists(List<String> allLists) {
        if (allLists == null || allLists.isEmpty()) {
            return Set.of();
        }

        var listWeights = allLists.stream()
                                  .collect(Collectors.groupingBy(Function.identity()))
                                  .entrySet().stream()
                                  .map(entry -> new AbstractMap.SimpleEntry<>(entry.getKey(), entry.getValue().size()))
                                  .sorted((e1, e2) -> e2.getValue() - e1.getValue())
                                  .collect(Collectors.toList());
        var listWeightsMax = listWeights.stream()
                                        .map(AbstractMap.SimpleEntry::getValue)
                                        .max(Comparator.comparingInt(entry -> entry))
                                        .orElseThrow();
        var threshold = listWeightsMax / filterDivider;
        return listWeights.stream()
                          .filter(entry -> entry.getValue() > threshold)
                          .map(AbstractMap.SimpleEntry::getKey)
                          .collect(Collectors.toSet());
    }

    private static boolean listsMatch(List<String> list1, List<String> list2) {
        if (list1 == null || list2 == null) {
            return list1 == list2;
        }
        if (list1.isEmpty() || list2.isEmpty()) {
            return list1.isEmpty() == list2.isEmpty();
        }

        var relevantLists1 = relevantLists(list1);
        var relevantLists2 = relevantLists(list2);

        return Objects.equals(relevantLists1, relevantLists2);
    }

    private static TrieEntry pruneEntry(TrieEntry root) {
        var newChildren = new TreeMap<String, TrieEntry>();
        if (root.children.isEmpty()) {
            return root;
        }

        for (var child : root.children.entrySet()) {
            newChildren.put(child.getKey(), pruneEntry(child.getValue()));
            root.children = newChildren;
        }
        var firstChild = root.children.firstEntry().getValue();
        var canBePruned = true;
        for (var child : root.children.entrySet()) {
            if (!child.getValue().children.isEmpty()) {
                canBePruned = false;
                break;
            }
            if (!listsMatch(child.getValue().values, firstChild.values)) {
                canBePruned = false;
                break;
            }
        }
        if (canBePruned) {
            if (root.values == null || listsMatch(root.values, firstChild.values)) {
                root.children.clear();
                root.values = firstChild.values;
            }
        }

        return root;
    }

    static Map<String, List<String>> stripDuplicatePrefixes(Map<String, List<String>> fullList) {
        // Create a prefix tree
        var trie = mapToTrie(fullList);

        // Prune it
        var pruned = pruneEntry(trie);

        // Restore the map from the tree
        return trieToMap(pruned, "");
    }

    static Map<String, Set<String>> pathListsToListPaths(Map<String, List<String>> pathLists) {
        var ret = new TreeMap<String, Set<String>>();

        for (var entry : pathLists.entrySet()) {
            var relevantLists = relevantLists(entry.getValue());
            for (var list : relevantLists) {
                if (!ret.containsKey(list)) {
                    ret.put(list, new TreeSet<>());
                }
                ret.get(list).add(entry.getKey());
            }
        }

        return ret;
    }

    static class RuleParser {
        private final Map<String, Set<Pattern>> matchers;
        private final Map<String, Set<String>> groups;

        RuleParser(String rulesFile) throws IOException {
            System.out.println("Reading rules file...");
            var rules = JSON.parse(Files.readString(Path.of(rulesFile)));

            matchers = rules.get("matchers").fields().stream()
                            .collect(Collectors.toMap(JSONObject.Field::name,
                                                      field -> field.value().stream()
                                                                    .map(JSONValue::asString)
                                                                    .map(s -> Pattern.compile("^" + s, Pattern.CASE_INSENSITIVE))
                                                                    .collect(Collectors.toSet())));
            groups = rules.get("groups").fields().stream()
                          .collect(Collectors.toMap(JSONObject.Field::name,
                                                    field -> field.value().stream()
                                                                  .map(JSONValue::asString)
                                                                  .collect(Collectors.toSet())));
        }

        Map<String, String> suggestedLists(String path) {
            var ret = new HashMap<String, String>();
            for (var rule : matchers.entrySet()) {
                for (var rulePath : rule.getValue()) {
                    var ruleMatcher = rulePath.matcher(path);
                    if (ruleMatcher.find()) {
                        ret.put(rule.getKey(), rulePath.toString());
                        break;
                    }
                }
            }

            return ret;
        }

        TreeSet<String> groupLists(Set<String> ungrouped) {
            var ret = new TreeSet<>(ungrouped);
            // If the current labels matches at least two members of a group, use the group instead
            for (var group : groups.entrySet()) {
                var count = 0;
                for (var groupEntry : group.getValue()) {
                    if (ret.contains(groupEntry)) {
                        count++;
                        if (count == 2) {
                            ret.add(group.getKey());
                            ret.removeAll(group.getValue());
                            break;
                        }
                    }
                }
            }
            return ret;
        }
    }

    private static void verifyInput(String rulesFile, Map<CommitMetadata, Set<String>> issueLists, Map<CommitMetadata, Set<String>> commitPaths) throws IOException {
        var ruleParser = new RuleParser(rulesFile);

        System.out.println("Verifying commits...");
        var matching = 0;
        var mismatch = 0;

        for (var issueList : issueLists.entrySet()) {
            if (issueList.getValue().isEmpty()) {
                // Ignore commits with unknown review list
                continue;
            }

            var suggestedLists = new TreeSet<String>();
            var pathMismatch = new HashMap<String, Set<String>>();
            for (var path : commitPaths.get(issueList.getKey())) {
                var suggestedForPath = ruleParser.suggestedLists(path);

                for (var suggested : suggestedForPath.entrySet()) {
                    if (!issueList.getValue().contains(suggested.getKey())) {
                        if (!pathMismatch.containsKey(path)) {
                            pathMismatch.put(path, new HashSet<>());
                        }
                        pathMismatch.get(path).add(suggested.getKey() + ": " + suggested.getValue());
                    }
                }
                suggestedLists.addAll(suggestedForPath.keySet());
            }

            var matchesExpected = issueList.getValue().stream()
                                           .anyMatch(l -> listFilterPattern.matcher(l).find());
            var matchesSuggested = suggestedLists.stream()
                                                 .anyMatch(l -> listFilterPattern.matcher(l).find());
            if (!matchesExpected && !matchesSuggested) {
                continue;
            }

            // Adjust suggestions according to grouping rules
            var suggestedListsGrouped = ruleParser.groupLists(suggestedLists);

            // Also see what the expected would look like with grouping
            var expectedGrouped = ruleParser.groupLists(issueList.getValue());

            if (suggestedListsGrouped.equals(issueList.getValue())) {
                System.out.println("✅ " + suggestedListsGrouped + " " + issueList.getKey().hash().abbreviate() + ": " + issueList.getKey().message().get(0));
                matching++;
            } else {
                if (suggestedListsGrouped.equals(expectedGrouped)) {
                    System.out.println("✅ " + issueList.getValue() + " -> " + suggestedListsGrouped + " " + issueList.getKey().hash().abbreviate() + ": " + issueList.getKey().message().get(0));
                    matching++;
                } else {
                    var missing = issueList.getValue().stream()
                                           .filter(value -> !suggestedLists.contains(value))
                                           .collect(Collectors.toSet());
                    var extra = suggestedLists.stream()
                                              .filter(value -> !issueList.getValue().contains(value))
                                              .collect(Collectors.toSet());
                    System.out.println("❌ " + issueList.getValue() + " " + issueList.getKey().hash().abbreviate() + ": " + issueList.getKey().message().get(0));
                    if (suggestedListsGrouped.equals(suggestedLists)) {
                        System.out.println("    Suggested lists: " + suggestedListsGrouped);
                    } else {
                        System.out.println("    Suggested lists: " + suggestedListsGrouped + " (ungrouped: " + suggestedLists + ")");
                    }

                    //System.out.println("Actual lists   : " + issueList.getValue());
                    //commitPaths.get(issueList.getKey()).forEach(s -> System.out.println("  " + s));
                    if (!extra.isEmpty()) {
                        System.out.println("    Rules matching unmentioned lists " + extra + ":");
                        for (var path : pathMismatch.entrySet()) {
                            System.out.println("      " + path.getKey() + " - " + path.getValue());
                        }
                    }
                    if (!missing.isEmpty()) {
                        var unmatched = commitPaths.get(issueList.getKey()).stream()
                                                   .filter(entry -> !pathMismatch.containsKey(entry))
                                                   .collect(Collectors.toList());
                        if (!unmatched.isEmpty()) {
                            System.out.println("    Files not matching any rule in " + missing + ":");
                            unmatched.forEach(s -> System.out.println("      " + s));
                        }
                    }
                    mismatch++;
                }
            }
        }

        System.out.println("Matches: " + matching + " - mismatches: " + mismatch);
    }

    public static void main(String[] args) throws IOException {
        var parser = new ArgumentParser("git skara debug mlrules", flags, inputs);
        var arguments = parser.parse(args);

        if (arguments.contains("verbose") || arguments.contains("debug")) {
            var level = arguments.contains("debug") ? Level.FINER : Level.FINE;
            Logging.setup(level);
        }
        if (arguments.contains("days")) {
            daysOfHistory = arguments.get("days").asInt();
        }
        if (arguments.contains("filter")) {
            filterDivider = arguments.get("filter").asInt();
        }
        if (arguments.contains("relaxed")) {
            reviewPattern = rfrSubjectOrIssue;
        }
        if (arguments.contains("lists")) {
            listFilterPattern = Pattern.compile(arguments.get("lists").asString());
        }

        var parsedLists = List.of("2d-dev",
                                  "awt-dev",
                                  "build-dev",
                                  "compiler-dev",
                                  "core-libs-dev",
                                  "hotspot-compiler-dev",
                                  "hotspot-gc-dev",
                                  "hotspot-jfr-dev",
                                  "hotspot-runtime-dev",
                                  "i18n-dev",
                                  "javadoc-dev",
                                  "net-dev",
                                  "nio-dev",
                                  "security-dev",
                                  "serviceability-dev",
                                  "sound-dev",
                                  "swing-dev");
        if (arguments.contains("verify")) {
            parsedLists = Stream.concat(parsedLists.stream(), List.of("hotspot-dev", "jdk-dev").stream())
                                .collect(Collectors.toList());
        }

        var repoPath = Path.of(arguments.at(0).asString()).toRealPath();
        if (repoPath.toFile().isFile()) {
            repoPath = repoPath.getParent();
        }
        var repo = ReadOnlyRepository.get(repoPath).orElseThrow();
        var repoRoot = repo.root();

        if (arguments.inputs().size() == 1 && repoPath.equals(repoRoot)) {
            System.out.println("Fetching commits metadata...");
            var cutoff = ZonedDateTime.now().minus(Duration.ofDays(daysOfHistory));
            var commits = repo.commitMetadata().stream()
                              .filter(commit -> commit.committed().isAfter(cutoff))
                              .collect(Collectors.toList());

            System.out.println("Done fetching commits metadata: " + commits.size() + " commits remaining after date filtering");

            var listReviews = listReviewedIssues(parsedLists.toArray(new String[0]));
            System.out.println("Done fetching mailing list archive pages");

            var issueLists = issueLists(commits, listReviews);
            var noReviewCount = issueLists.entrySet().stream()
                                          .filter(entry -> entry.getValue().isEmpty())
                                          .count();
            System.out.println("Done mapping commit issues to lists: " + noReviewCount + " commits have no matching review");

            for (var issue : issueLists.entrySet()) {
                if (!issue.getValue().isEmpty()) {
                    log.fine(issue.getKey().hash().abbreviate() + ": " + issue.getKey().message().get(0) + ": " + issue.getValue());
                }
            }

            System.out.print("Fetching commit changes: ");
            var commitPaths = commitPaths(repo, issueLists.keySet());
            for (var commitPath : commitPaths.entrySet()) {
                log.fine(commitPath.getKey().hash().abbreviate() + ": " + commitPath.getValue());
            }
            System.out.println(" done");

            System.out.println("Fetching list of existing files...");
            var currentPaths = repo.files(repo.head(), List.of()).stream()
                                   .map(FileEntry::path)
                                   .filter(Objects::nonNull)
                                   .map(Path::toString)
                                   .collect(Collectors.toSet());


            var existingCommitPaths = commitPaths.entrySet().stream()
                                                 .collect(Collectors.toMap(Map.Entry::getKey,
                                                                           entry -> entry.getValue().stream()
                                                                                         .filter(currentPaths::contains)
                                                                                         .collect(Collectors.toSet())));

            if (arguments.contains("verify")) {
                verifyInput(arguments.get("verify").asString(), issueLists, existingCommitPaths);
                return;
            }

            var pathLists = pathLists(issueLists, existingCommitPaths);
            var unknownPaths = currentPaths.stream()
                                           .filter(p -> !pathLists.containsKey(p))
                                           .collect(Collectors.toCollection(TreeSet::new));

            var uniquePathLists = stripDuplicatePrefixes(pathLists);
            for (var pathList : uniquePathLists.entrySet()) {
                var relevantLists = relevantLists(pathList.getValue());
                log.fine(pathList.getKey() + ": " + relevantLists);
            }

            var listPaths = pathListsToListPaths(uniquePathLists);
            listPaths.put("unknown", unknownPaths);

            var finalResult = "{\n" + listPaths.entrySet().stream()
                                               .map(entry -> "    \"" + entry.getKey() + "\": [\n" +
                                                       entry.getValue().stream()
                                                            .map(path -> "        \"" + path + "\"")
                                                            .collect(Collectors.joining(",\n")) +
                                                       "\n    ]")
                                               .collect(Collectors.joining(",\n")) +
                    "\n}";
            if (arguments.contains("output")) {
                System.out.println("Writing final output to " + arguments.get("output").asString());
                Files.writeString(Path.of(arguments.get("output").asString()), finalResult);
            } else {
                System.out.println(finalResult);
            }
        } else if (arguments.inputs().size() >= 1 && arguments.contains("verify")) {
            var requestedFiles = new HashSet<String>();
            for (var input : arguments.inputs()) {
                var path = Path.of(input.asString());
                try {
                    // Normalize, if possible
                    path = path.toRealPath();
                } catch (IOException ioe) {
                    // If the file does not exist, use the name as-is
                }
                if (path.toFile().isFile()) {
                    requestedFiles.add(repoRoot.relativize(path).toString());
                } else {
                    try (var paths = Files.walk(path)) {
                        paths.filter(p -> p.toFile().isFile())
                             .map(p -> repoRoot.relativize(p).toString())
                             .forEach(requestedFiles::add);
                    }
                }
            }

            var ruleParser = new RuleParser(arguments.get("verify").asString());
            var pathLists = requestedFiles.stream()
                                          .collect(Collectors.toMap(Function.identity(),
                                                                    p -> (List<String>)new ArrayList<>(ruleParser.suggestedLists(p).keySet())));
            var uniquePathLists = stripDuplicatePrefixes(pathLists);
            var suggestedLists = new TreeSet<String>();
            for (var uniquePath : uniquePathLists.entrySet()) {
                System.out.println(uniquePath.getKey() + ": " + uniquePath.getValue());
                suggestedLists.addAll(uniquePath.getValue());
            }
            System.out.println();
            System.out.println("Combined list suggestion: " + suggestedLists);
            System.out.println("Final list suggestion is: " + ruleParser.groupLists(suggestedLists));
        } else {
            System.out.println("To generate a rules list from parsing review archives:");
            System.out.println("  git skara mlrules <repository root> [--filter X] [--days D] [--output FILE]");
            System.out.println();
            System.out.println("To verify a rules list against historical commits and reviews:");
            System.out.println("  git skara mlrules <repository root> [--verify CONFIG-FILE] [--days D]");
            System.out.println();
            System.out.println("To verify a config file against a given list of files/directories in a repository:");
            System.out.println("  git skara mlrules --verify CONFIG-FILE <file1/dir1> [<file2/dir2> <file3/dir3>...]");
            System.out.println();
            System.out.println("For the full list of options:");
            System.out.println("  git skara mlrules --help");
        }
    }
}
