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
- forward - forward commits to various repositories
- mirror - mirror repositories
- merge - merge commits between different repositories and/or branches
- test - test runner

## Building

[JDK 13](http://jdk.java.net/13/) or later and [Gradle](https://gradle.org/)
6.0 or later is required for building. To build the project on macOS or
GNU/Linux x64, just run the following command from the source tree root:

```bash
$ sh gradlew
```

To build the project on Windows x64, run the following command from the source tree root:

```bat
> gradlew
```

The extracted jlinked image will end up in the `build` directory in the source
tree root. _Note_ that the above commands will build the CLI tools, if you
also want to build the bot images run `sh gradlew images` on GNU/Linux or
`gradlew images` on Windows.

### Other operating systems and CPU architectures

If you want to build on an operating system other than GNU/Linux, macOS or
Windows _or_ if you want to build on a CPU architecture other than x64, then
ensure that you have JDK 13 or later installed locally. You can then run the
following command from the source tree root:

```bash
$ sh gradlew
```

The extracted jlinked image will end up in the `build` directory in the source
tree root.

### Offline builds

If you don't want the build to automatically download any dependencies, then
you must ensure that you have installed the following software locally:

- JDK 13 or later
- Gradle 6.0 or later

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

### Makefile wrapper

Skara also has a very thin Makefile wrapper for contributors who prefer to build
using `make`. To build the jlinked image for the CLI tools using `make`, run:

```bash
make
```

## Installing

There are multiple way to install the Skara CLI tools. The easiest way is to
just include `skara.gitconfig` in your global Git configuration file. You can also
install the Skara tools on your `$PATH`.

### Including skara.gitconfig

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

### Adding to PATH

The Skara tools can also be added to `$PATH` on GNU/Linux and macOS and Git
will pick them up. You can either just extend `$PATH` with the `build/bin`
directory or you can copy the tools to a location already on `$PATH`. To extend
`$PATH` with the `build/bin` directory, run:

```bash
$ sh gradlew
$ export PATH="$PWD/build/bin:$PATH"
```

To copy the tools to a location already on `$PATH`, run:

```bash
$ make
$ make install prefix=/path/to/install/location
```

When running `make install` the default value of `prefix` is `$HOME/.local`.

## Testing

[JUnit](https://junit.org/junit5/) 5.5.2 or later is required to run the unit
tests. To run the tests, execute following command from the source tree root:

```bash
$ sh gradlew test
```

If you prefer to use the Makefile wrapper you can also run:

```bash
$ make test
```

The tests expect [Git](https://git-scm.com/) version 2.19.3 or later and
[Mercurial](https://mercurial-scm.org/) 4.7.2 or later to be installed on
your system.

This repository also contains a Dockerfile, `test.dockerfile`, that allows
for running the tests in a reproducible way with the proper dependencies
configured. To run the tests in this way, run the following command from the
source tree root:

```bash
$ sh gradlew reproduce
```

If you prefer to use the Makefile wrapper you can also run:

```bash
$ make reproduce
```

## Developing

There are no additional dependencies required for developing Skara if you can
already build and test it (see above for instructions). The command-line tools
and libraries supports all of GNU/Linux, macOS and Windows and can therefore be
developed on any of those operating systems. The bots primarily support macOS
and GNU/Linux and may require [Windows Subsystem for
Linux](https://en.wikipedia.org/wiki/Windows_Subsystem_for_Linux) on Windows.

Please see the sections below for instructions on setting up a particular editor
or IDE.

### Vim

If you choose to use [Vim](https://vim.org) as your editor when working on Skara then you
probably also want to utilize the Makefile wrapper. The Makefile wrapper enables
to you to run `:make` and `:make tests` in Vim.

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
