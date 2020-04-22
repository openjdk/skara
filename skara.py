# Copyright (c) 2019, 2020, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.

import mercurial
import os
import os.path
import subprocess
import sys
import shutil

testedwith = '4.9.2 5.3'

cmdtable = {}
if hasattr(mercurial, 'registrar') and hasattr(mercurial.registrar, 'command'):
    command = mercurial.registrar.command(cmdtable)
elif hasattr(mercurial.cmdutil, 'command'):
    command = mercurial.cmdutil.command(cmdtable)
else:
    def command(name, options, synopsis):
        def decorator(func):
            cmdtable[name] = func, list(options), synopsis
            return func
        return decorator

def _skara(ui, args, **opts):
    skara = os.path.dirname(os.path.realpath(__file__))
    git_skara = os.path.join(skara, 'bin', 'bin', 'git-skara')
    if not os.path.isfile(git_skara):
        ui.status(b'Compiling ...\n')
        cmd = ['gradlew.bat'] if os.name == 'nt' else ['/bin/sh', 'gradlew']
        p = subprocess.Popen(cmd, cwd=skara)
        ret = p.wait()
        if ret != 0:
            ui.error(b"Error: could not compile Skara\n")
            sys.exit(1)

    skara_bin = os.path.join(skara, 'bin')
    skara_build = os.path.join(skara, 'build')
    if os.path.isdir(skara_build):
        if os.path.isdir(skara_bin):
            shutil.rmtree(skara_bin)
        shutil.move(skara_build, skara_bin)

    for k in opts:
        if opts[k] == True:
            args.append('--' + k.replace('_', '-'))
        elif opts[k] != b'' and opts[k] != False:
            args.append('--' + k)
            args.append(opts[k])
    return subprocess.call([git_skara] + args)

skara_opts = [
]
@command(b'skara', skara_opts, b'hg skara <defpath|help|jcheck|version|webrev|update>')
def skara(ui, repo, action=None, **opts):
    """
    Invoke, list or update Mercurial commands from project Skara
    """
    if action == None:
        action = 'help'
    sys.exit(_skara(ui, [action, '--mercurial'], **opts))

webrev_opts = [
    (b'r', b'rev', b'', b'Compare against specified revision'),
    (b'o', b'output', b'', b'Output directory'),
    (b'u', b'username', b'', b'Use that username instead "guessing" one'),
    (b'',  b'upstream', b'', b'The URL to the upstream repository'),
    (b't', b'title', b'', b'The title of the webrev'),
    (b'c', b'cr', b'', b'Include a link to CR (aka bugid) in the main page'),
    (b'b', b'b', False, b'Do not ignore changes in whitespace'),
    (b'C', b'no-comments', False, b"Don't show comments"),
    (b'N', b'no-outgoing', False, b"Do not compare against remote, use only 'status'"),
]
@command(b'webrev', webrev_opts, b'hg webrev')
def webrev(ui, repo, **opts):
    """
    Builds a set of HTML files suitable for doing a
    code review of source changes via a web page
    """
    sys.exit(_skara(ui, ['webrev', '--mercurial'], **opts))

jcheck_opts = [
    (b'r', b'rev', b'', b'Check the specified revision or range (default: tip)'),
    (b'',  b'whitelist', b'', b'Use specified whitelist (default: .jcheck/whitelist.json)'),
    (b'',  b'blacklist', b'', b'Use specified blacklist (default: .jcheck/blacklist.json)'),
    (b'',  b'census', b'', b'Use the specified census (default: https://openjdk.java.net/census.xml)'),
    (b'',  b'local', False, b'Run jcheck in "local" mode'),
    (b'',  b'lax', False, b'Check comments, tags and whitespace laxly'),
    (b's', b'strict', False, b'Check everything')
]
@command(b'jcheck', jcheck_opts, b'hg jcheck')
def jcheck(ui, repo, **opts):
    """
    OpenJDK changeset checker
    """
    sys.exit(_skara(ui, ['jcheck', '--mercurial'], **opts))

defpath_opts = [
    (b'u', b'username', b'', b'Username for push URL'),
    (b's', b'secondary', b'', b'Secondary peer repostiory base URL'),
    (b'd', b'default', False, b'Use current default path to compute push path'),
    (b'g', b'gated', False, b'Created gated push URL'),
    (b'n', b'dry-run', False, b'Do not perform actions, just print output'),
]
@command(b'defpath', defpath_opts, b'hg defpath')
def defpath(ui, repo, **opts):
    """
    Examine and manipulate default path settings
    """
    sys.exit(_skara(ui, ['defpath', '--mercurial'], **opts))
