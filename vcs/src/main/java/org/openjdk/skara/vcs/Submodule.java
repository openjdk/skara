package org.openjdk.skara.vcs;

import java.nio.file.Path;
import java.util.Objects;

public class Submodule {
    private final Hash hash;
    private final Path path;
    private final String pullPath;

    public Submodule(Hash hash, Path path, String pullPath) {
        this.hash = hash;
        this.path = path;
        this.pullPath = pullPath;
    }

    public Hash hash() {
        return hash;
    }

    public Path path() {
        return path;
    }

    public String pullPath() {
        return pullPath;
    }

    @Override
    public String toString() {
        return pullPath + " " + hash + " " + path;
    }

    @Override
    public int hashCode() {
        return Objects.hash(hash, path, pullPath);
    }

    @Override
    public boolean equals(Object other) {
        if (other == this) {
            return true;
        }

        if (!(other instanceof Submodule)) {
            return false;
        }

        var o = (Submodule) other;
        return Objects.equals(hash, o.hash) &&
               Objects.equals(path, o.path) &&
               Objects.equals(pullPath, o.pullPath);
    }
}
