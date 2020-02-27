# OpenJDK Project Skara

The goal of Project Skara is to investigate alternative SCM and code review
options for the OpenJDK source code, including options based upon Git rather than
Mercurial, and including options hosted by third parties.

This repository contains tooling for working with OpenJDK projects and
their repositories. The following CLI tools are available as part of this
repository:

- git-jcheck - a backwards compatible Git port of [jcheck](https://openjdk.java.net/projects/code-tools/jcheck/)
- git-webrev - a backwards compatible Git port of [webrev](https://openjdk.java.net/projects/code-tools/webrev/)
- git-defpath - a backwards compatible Git port of [defpath](https://openjdk.java.net/projects/code-tools/defpath/)
- git-fork - fork a project on an external Git source code hosting provider to your personal space and optionally clone it
- git-sync - sync the personal fork of the project with the current state of the upstream repository
- git-pr - interact with pull requests for a project on an external Git source code hosting provider
- git-info - show OpenJDK information about commits, e.g. issue links, authors, contributors, etc.
- git-token - interact with a Git credential manager for handling personal access tokens
- git-translate - translate between [Mercurial](https://mercurial-scm.org/)
and [Git](https://git-scm.com/) hashes
- git-skara - learn about and update the Skara CLI tools
- git-publish - publishes a local branch to a remote repository

There are also CLI tools available for importing OpenJDK
[Mercurial](https://mercurial-scm.org/) repositories into
[Git](https://git-scm.com/) repositories and vice versa:

- git-openjdk-import
- git-verify-import
- hg-openjdk-import

The following server-side tools (so called "bots") for interacting with
external Git source code hosting providers are available:

- hgbridge - continuously convert Mercurial repositories to git
- mlbridge - bridge messages between mailing lists and pull requests
- notify - send email notifications when repositories are updated
- pr - add OpenJDK workflow support for pull requests
- submit - example pull request test runner

## Building

[JDK 12](http://jdk.java.net/12/) or later and [Gradle](https://gradle.org/)
5.6.2 or later is required for building. To build the project on macOS or
GNU/Linux x64, just run the following command from the source tree root:

```bash
$ sh gradlew
```

To build the project on Windows x64, run the following command from the source tree root:

```bat
> gradlew
```

The extracted jlinked image will end up in the `build` directory in the source
tree root.

### Other operating systems and CPU architectures

If you want to build on an operating system other than GNU/Linux, macOS or
Windows _or_ if you want to build on a CPU architecture other than x64, then
ensure that you have JDK 12 or later installed locally. You can then run the
following command from the source tree root:

```bash
$ sh gradlew
```

The extracted jlinked image will end up in the `build` directory in the source
tree root.

### Offline builds

If you don't want the build to automatically download any dependencies, then
you must ensure that you have installed the following software locally:

- JDK 12 or later
- Gradle 5.6.2 or later

To create a build then run the command:

```bash
$ gradle offline
```

_Please note_ that the above command does _not_ make use of `gradlew` to avoid
downloading Gradle.

The extracted jlinked image will end up in the `build` directory in the source
tree root.

### Cross-linking

It is also supported to cross-jlink jimages to GNU/Linux, macOS and/or Windows from
any of the aforementioned operating systems. To build all applicable jimages
(including the server-side tooling), run the following command from the
source tree root:

```bash
sh gradlew images
```

## Installing

To install the Skara tools, include the `skara.gitconfig` Git configuration
file in your user-level Git configuration file. On macOS or
GNU/Linux:

```bash
$ git config --global include.path "$PWD/skara.gitconfig"
```

On Windows:

```bat
> git config --global include.path "%CD%/skara.gitconfig"
```

To check that everything works as expected, run the command `git skara help`.

## Testing

[JUnit](https://junit.org/junit5/) 5.5.1 or later is required to run the unit
tests. To run the tests, execute following command from the source tree root:

```bash
$ sh gradlew test
```

The tests expect [Git](https://git-scm.com/) version 2.19.1 or later and
[Mercurial](https://mercurial-scm.org/) 4.7.1 or later to be installed on
your system.

This repository also contains a Dockerfile, `test.dockerfile`, that allows
for running the tests in a reproducible way with the proper dependencies
configured. To run the tests in this way, run the following command from the
source tree root:

```bash
$ sh gradlew reproduce
```

## Wiki

Project Skara's wiki is available at <https://wiki.openjdk.java.net/display/skara>.

## Issues

Issues are tracked in the [JDK Bug System](https://bugs.openjdk.java.net/)
under project Skara at <https://bugs.openjdk.java.net/projects/SKARA/>.

## Contributing

We are more than happy to accept contributions to the Skara tooling, both via
patches sent to the Skara
[mailing list](https://mail.openjdk.java.net/mailman/listinfo/skara-dev) and in the
form of pull requests on [GitHub](https://github.com/openjdk/skara/pulls/).

## Members

See <http://openjdk.java.net/census#skara> for the current Skara
[Reviewers](https://openjdk.java.net/bylaws#reviewer),
[Committers](https://openjdk.java.net/bylaws#committer) and
[Authors](https://openjdk.java.net/bylaws#author). See
<https://openjdk.java.net/projects/> for how to become an author, committer
or reviewer in an OpenJDK project.

## Discuss

Development discussions take place on the project Skara mailing list
`skara-dev@openjdk.java.net`, see
<https://mail.openjdk.java.net/mailman/listinfo/skara-dev> for instructions
on how to subscribe of if you want to read the archives. You can also reach
many project Skara developers in the `#openjdk` IRC channel on
[OFTC](https://www.oftc.net/), see <https://openjdk.java.net/irc/> for details.

## License

See the file `LICENSE` for details.
