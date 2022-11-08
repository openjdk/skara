package org.openjdk.skara.jcheck;

import org.junit.jupiter.api.Test;
import org.openjdk.skara.vcs.*;
import org.openjdk.skara.vcs.openjdk.CommitMessage;
import org.openjdk.skara.vcs.openjdk.CommitMessageParsers;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class JCheckConfCheckTests {

    private static class JCheckConfTestRepository extends TestRepository {
        List<String> conf;

        public JCheckConfTestRepository(List<String> text) {
            conf = List.copyOf(text);
        }

        @Override
        public Optional<List<String>> lines(Path p, Hash h) throws IOException {
            if (p.toString().equals(".jcheck/conf")) {
                return Optional.of(conf);
            }
            return super.lines(p, h);
        }

        public void setConf(List<String> text) {
            conf = List.copyOf(text);
        }
    }

    private static final List<String> CONFIGURATION = List.of(
            "[general]",
            "project = test",
            "[checks]",
            "error = jcheckconf"
    );

    private static final JCheckConfiguration conf = JCheckConfiguration.parse(CONFIGURATION);

    private static ReadOnlyRepository repo = new JCheckConfTestRepository(CONFIGURATION);

    private List<Issue> toList(Iterator<Issue> i) {
        var list = new ArrayList<Issue>();
        while (i.hasNext()) {
            list.add(i.next());
        }
        return list;
    }

    private static Commit commit(int id, String... message) {
        var author = new Author("foo", "foo@host.org");
        var hash = new Hash(("" + id).repeat(40));
        var parents = List.of(Hash.zero());
        var authored = ZonedDateTime.now();
        var metadata = new CommitMetadata(hash, parents, author, authored, author, authored, List.of(message));
        return new Commit(metadata, List.of());
    }

    private static CommitMessage message(Commit c) {
        return CommitMessageParsers.v1.parse(c);
    }

    @Test
    void validJCheckConfTest() {
        var commit = commit(0, "Bugfix");
        var message = message(commit);
        var check = new JCheckConfCheck(repo);
        var issues = toList(check.check(commit, message, conf, null));
        assertEquals(0, issues.size());
    }

    @Test
    void invalidJCheckConfTest() {
        var commit = commit(0, "Bugfix");
        var message = message(commit);
        var check = new JCheckConfCheck(repo);

        ((JCheckConfTestRepository) repo).setConf(List.of(
                "[general] 36542",
                "project = test",
                "[checks]",
                "error = jcheckconf"
        ));
        var issues = toList(check.check(commit, message, conf, null));
        assertEquals(1, issues.size());
        assertEquals("line 0: section header must end with ']'", ((JCheckConfIssue) issues.get(0)).getErrorMessage());

        ((JCheckConfTestRepository) repo).setConf(List.of(
                "[general]",
                "project = test",
                "[checks]",
                "error = jcheckconf",
                "randomrandom"
        ));
        issues = toList(check.check(commit, message, conf, null));
        assertEquals(1, issues.size());
        assertEquals("line 4: entry must be of form 'key = value'", ((JCheckConfIssue) issues.get(0)).getErrorMessage());
    }
}
