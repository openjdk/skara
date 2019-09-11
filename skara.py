# Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
import os.path
import subprocess
import sys
import shutil

testedwith = '4.9.2'

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
    for k in opts:
        if opts[k] == True:
            args.append('--' + k.replace('_', '-'))
        elif opts[k] != '' and opts[k] != False:
            args.append('--' + k)
            args.append(opts[k])
    skara = os.path.dirname(os.path.realpath(__file__))
    git_skara = os.path.join(skara, 'bin', 'bin', 'git-skara')
    if not os.path.isfile(git_skara):
        ui.status("Bootstrapping Skara itself...\n")
        p = subprocess.Popen(['/bin/sh', 'gradlew'], cwd=skara)
        ret = p.wait()
        if ret != 0:
            ui.error("Error: could not bootstrap Skara\n")
            sys.exit(1)

    skara_bin = os.path.join(skara, 'bin')
    skara_build = os.path.join(skara, 'build')
    if os.path.isdir(skara_build):
        if os.path.isdir(skara_bin):
            shutil.rmtree(skara_bin)
        shutil.move(skara_build, skara_bin)

    sys.exit(subprocess.call([git_skara] + args))

def _web_url(url):
    if url.startswith('git+'):
        url = url[len('git+'):]

    if url.startswith('http'):
        return url

    if not url.startswith('ssh://'):
        raise ValueError('Unexpected url: ' + url)

    without_protocol = url[len('ssh://'):]
    first_slash = without_protocol.index('/')
    host = without_protocol[:first_slash]

    ssh_config = os.path.join(os.path.expanduser('~'), '.ssh', 'config')
    if os.path.exists(ssh_config):
        with open(ssh_config) as f:
            lines = f.readlines()
            current = None
            for line in lines:
                if line.startswith('Host '):
                    current = line.split(' ')[1].strip()
                if line.strip().lower().startswith('hostname') and host == current:
                    host = line.strip().split(' ')[1]
                    break

    return 'https://' + host + without_protocol[first_slash:]

def _username(ui, opts, url):
    web_url = _web_url(url)
    username = None
    if opts.get('username') == '':
        username = ui.config('credential "' + web_url + '"', 'username')
        if username == None:
            protocol, rest = web_url.split('://')
            hostname = rest[:rest.index('/')]
            username = ui.config('credential "' + protocol + '://' + hostname + '"', 'username')
            if username == None:
                username = ui.config('credential', 'username')
    return username

fork_opts = [
    ('u', 'username', '', 'Username on host'),
]
@command('fork', fork_opts, 'hg fork URL [DEST]', norepo=True)
def fork(ui, url, dest=None, **opts):
    username = _username(ui, opts, url)
    args = ['fork', '--mercurial']
    if username != None:
        args.append("--username")
        args.append(username)
    args.append(url)
    if dest != None:
        args.append(dest)
    _skara(ui, args)

webrev_opts = [
    ('r', 'rev', '', 'Compare against specified revision'),
    ('o', 'output', '', 'Output directory'),
    ('u', 'username', '', 'Use that username instead "guessing" one'),
    ('',  'upstream', '', 'The URL to the upstream repository'),
    ('t', 'title', '', 'The title of the webrev'),
    ('c', 'cr', '', 'Include a link to CR (aka bugid) in the main page'),
    ('b', 'b', False, 'Do not ignore changes in whitespace'),
    ('C', 'no-comments', False, "Don't show comments"),
    ('N', 'no-outgoing', False, "Do not compare against remote, use only 'status'"),

]
@command('webrev', webrev_opts, 'hg webrev')
def webrev(ui, repo, **opts):
    _skara(ui, ['webrev', '--mercurial'], **opts)

jcheck_opts = [
    ('r', 'rev', '', 'Check the specified revision or range (default: tip)'),
    ('',  'whitelist', '', 'Use specified whitelist (default: .jcheck/whitelist.json)'),
    ('',  'blacklist', '', 'Use specified blacklist (default: .jcheck/blacklist.json)'),
    ('',  'census', '', 'Use the specified census (default: https://openjdk.java.net/census.xml)'),
    ('',  'local', False, 'Run jcheck in "local" mode'),
    ('',  'lax', False, 'Check comments, tags and whitespace laxly'),
    ('s', 'strict', False, 'Check everything')
]
@command('jcheck', jcheck_opts, 'hg jcheck')
def jcheck(ui, repo, **opts):
    _skara(ui, ['jcheck', '--mercurial'], **opts)

defpath_opts = [
    ('u', 'username', '', 'Username for push URL'),
    ('r', 'remote', '', 'Remote for which to set paths'),
    ('s', 'secondary', '', 'Secondary peer repostiory base URL'),
    ('d', 'default', False, 'Use current default path to compute push path'),
    ('g', 'gated', False, 'Created gated push URL'),
    ('n', 'dry-run', False, 'Do not perform actions, just print output'),
]
@command('defpath', defpath_opts, 'hg defpath')
def defpath(ui, repo, **opts):
    _skara(ui, ['defpath', '--mercurial'], **opts)

info_opts = [
    ('', 'no-decoration', False, 'Do not prefix lines with any decoration'),
    ('', 'issues', False, 'Show issues'),
    ('', 'reviewers', False, 'Show reviewers'),
    ('', 'summary', False, 'Show summary (if present)'),
    ('', 'sponsor', False, 'Show sponsor (if present)'),
    ('', 'author', False, 'Show author'),
    ('', 'contributors', False, 'Show contributors')
]
@command('info', info_opts, 'hg info')
def info(ui, repo, rev, **opts):
    _skara(ui, ['info', '--mercurial', rev], **opts)

pr_opts = [
    ('u', 'username', '', 'Username on host'),
    ('r', 'remote', '', 'Name of path, defaults to "default"'),
    ('b', 'branch', '', 'Name of target branch, defaults to "default"'),
    ('',  'authors', '', 'Comma separated list of authors'),
    ('',  'assignees', '', 'Comma separated list of assignees'),
    ('',  'labels', '', 'Comma separated list of labels'),
    ('',  'columns', '', 'Comma separated list of columns to show'),
    ('', 'no-decoration', False, 'Do not prefix lines with any decoration')
]
@command('pr', pr_opts, 'hg pr <list|fetch|show|checkout|apply|integrate|approve|create|close|update>')
def pr(ui, repo, action, n=None, **opts):
    path = opts.get('remote')
    if path == '':
        path = 'default'
    url = ui.config('paths', path)
    username = _username(ui, opts, url)
    args = ['pr', '--mercurial']
    if username != None:
        args.append('--username')
        args.append(username)
    args.append(action)
    if n != None:
        args.append(n)
    _skara(ui, args, **opts)
