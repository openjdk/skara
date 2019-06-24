/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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

package org.openjdk.skara.gradle.images;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.tasks.TaskAction;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.io.File;
import java.util.Comparator;

public class DownloadJDKTask extends DefaultTask {
    private String url;
    private String sha256;
    private Path toDir;

    void setUrl(String url) {
        this.url = url;
    }

    void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    void setToDir(Path toDir) {
        this.toDir = toDir;
    }

    private static String checksum(Path file) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var bytes = new byte[4096];
            try (var stream = Files.newInputStream(file)) {
                for (var read = stream.read(bytes); read != -1; read = stream.read(bytes)) {
                    digest.update(bytes, 0, read);
                }
            }
            return new BigInteger(1, digest.digest()).toString(16);
        } catch (NoSuchAlgorithmException e) {
            throw new GradleException("this JRE does not support SHA-256");
        }
    }

    private static void clearDirectory(Path directory) throws IOException {
        Files.walk(directory)
                .map(Path::toFile)
                .sorted(Comparator.reverseOrder())
                .forEach(File::delete);
    }

    private void unpack(Path file, Path dist) throws IOException {
        if (Files.isDirectory(dist)) {
            clearDirectory(dist);
        } else {
            Files.createDirectories(dist);
        }

        var project = getProject().getRootProject();
        project.copy((spec) -> {
            var path = file.toString();
            if (path.endsWith(".tar.gz")) {
                spec.from(project.tarTree(path));
            } else if (path.endsWith(".zip")) {
                spec.from(project.zipTree(path));
            }
            spec.into(dist.toString());
        });
    }

    @TaskAction
    void download() throws IOException, InterruptedException {
        var uri = URI.create(url);
        var filename = Path.of(uri.getPath()).getFileName().toString();
        var file = toDir.resolve(filename).toAbsolutePath();
        var dist = toDir.resolve(filename.replace(".zip", "").replace(".tar.gz", ""));

        if (Files.exists(dist) && Files.isDirectory(dist)) {
            return;
        }

        if (Files.exists(file)) {
            var sum = checksum(file);
            if (sum.equals(sha256)) {
                unpack(file, dist);
                return;
            } else {
                Files.delete(file);
            }
        }

        if (!Files.exists(toDir)) {
            Files.createDirectories(toDir);
        }

        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder()
                             .uri(uri)
                             .build();

        var res = client.send(req, BodyHandlers.ofFile(file));
        if (res.statusCode() >= 300) {
            throw new GradleException("could not download " + url + ", got " + res.statusCode());
        }

        var sum = checksum(file);
        if (!sum.equals(sha256)) {
            throw new GradleException("checksums do not match, actual: " + sum + ", expected: " + sha256);
        }

        unpack(file, dist);
    }
}
