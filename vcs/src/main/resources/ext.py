# Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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
import mercurial.patch
import mercurial.mdiff
import mercurial.util
import difflib
import sys

# space separated version list
testedwith = '4.9.2 5.0.2'

def mode(fctx):
    flags = fctx.flags()
    if flags == '': return '100644'
    if flags == 'x': return '100755'
    if flags == 'l': return '120000'

def ratio(a, b, threshold):
    s = difflib.SequenceMatcher(None, a, b)
    if s.real_quick_ratio() < threshold:
        return 0
    if s.quick_ratio() < threshold:
        return 0
    ratio = s.ratio()
    if ratio < threshold:
        return 0
    return ratio

def encode(s):
    return s.decode('utf-8').encode('utf-8')

def write(s):
    sys.stdout.write(encode(s))

def writeln(s):
    write(s)
    sys.stdout.write(encode('\n'))

def _match_exact(root, cwd, files, badfn=None):
    """
    Wrapper for mercurial.match.exact that ignores some arguments based on the used version
    """
    if mercurial.util.version().startswith("5"):
        return mercurial.match.exact(files, badfn)
    else:
        return mercurial.match.exact(root, cwd, files, badfn)

def _diff_git_raw(repo, ctx1, ctx2, modified, added, removed):
    nullHash = '0' * 40
    removed_copy = set(removed)

    for path in added:
        fctx = ctx2.filectx(path)
        if fctx.renamed():
            parent = fctx.p1()
            old_path, _ = fctx.renamed()
            if old_path in removed:
                removed_copy.discard(old_path)

    for path in sorted(modified | added | removed_copy):
        if path in modified:
            fctx = ctx2.filectx(path)
            writeln(':{} {} {} {} M\t{}'.format(mode(ctx1.filectx(path)), mode(fctx), nullHash, nullHash, fctx.path()))
        elif path in added:
            fctx = ctx2.filectx(path)
            if not fctx.renamed():
                writeln(':000000 {} {} {} A\t{}'.format(mode(fctx), nullHash, nullHash, fctx.path()))
            else:
                parent = fctx.p1()
                score = int(ratio(parent.data(), fctx.data(), 0.5) * 100)
                old_path, _ = fctx.renamed()

                if old_path in removed:
                    operation = 'R'
                else:
                    operation = 'C'

                writeln(':{} {} {} {} {}{}\t{}\t{}'.format(mode(parent), mode(fctx), nullHash, nullHash, operation, score, old_path, path))
        elif path in removed_copy:
            fctx = ctx1.filectx(path)
            writeln(':{} 000000 {} {} D\t{}'.format(mode(fctx), nullHash, nullHash, path))

    writeln('')

    match = _match_exact(repo.root, repo.getcwd(), list(modified) + list(added) + list(removed_copy))
    opts = mercurial.mdiff.diffopts(git=True, nodates=True, context=0, showfunc=True)
    for d in mercurial.patch.diff(repo, ctx1.node(), ctx2.node(), match=match, opts=opts):
        sys.stdout.write(d)

def really_differs(repo, p1, p2, ctx, files):
    # workaround bug in hg (present since forever):
    # `hg status` can, for merge commits, report a file as modififed between one parent
    # and the merge even though it isn't. `hg diff` works correctly, so remove any "modified"
    # that has an empty diff against one of its parents
    differs = set()
    for path in files:
        match = _match_exact(repo.root, repo.getcwd(), [path])
        opts = mercurial.mdiff.diffopts(git=True, nodates=True, context=0, showfunc=True)

        diff1 = mercurial.patch.diff(repo, p1.node(), ctx.node(), match=match, opts=opts)
        diff2 = mercurial.patch.diff(repo, p2.node(), ctx.node(), match=match, opts=opts)
        if len(list(diff1)) > 0 and len(list(diff2)) > 0:
            differs.add(path)

    return differs

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

if hasattr(mercurial, 'utils') and hasattr(mercurial.utils, 'dateutil'):
    datestr = mercurial.utils.dateutil.datestr
else:
    datestr = mercurial.util.datestr

if hasattr(mercurial, 'scmutil'):
    revsingle = mercurial.scmutil.revsingle
    revrange = mercurial.scmutil.revrange
else:
    revsingle = mercurial.cmdutil.revsingle
    revrange = mercurial.cmdutil.revrange

@command('diff-git-raw', [], 'hg diff-git-raw rev1 [rev2]')
def diff_git_raw(ui, repo, rev1, rev2=None, **opts):
    ctx1 = revsingle(repo, rev1)

    if rev2 != None:
        ctx2 = revsingle(repo, rev2)
        status = repo.status(ctx1, ctx2)
    else:
        ctx2 = mercurial.context.workingctx(repo)
        status = repo.status(ctx1)

    modified, added, removed = [set(l) for l in status[:3]]
    _diff_git_raw(repo, ctx1, ctx2, modified, added, removed)

@command('log-git', [('', 'reverse', False, ''), ('l', 'limit', -1, '')],  'hg log-git <revisions>')
def log_git(ui, repo, revs=None, **opts):
    if len(repo) == 0:
        return

    if revs == None:
        if opts['reverse']:
            revs = '0:tip'
        else:
            revs = 'tip:0'

    limit = opts['limit']
    i = 0
    for r in revrange(repo, [revs]):
        ctx = repo[r]

        __dump_metadata(ctx)
        parents = ctx.parents()

        if len(parents) == 1:
            modified, added, removed = [set(l) for l in repo.status(parents[0], ctx)[:3]]
            _diff_git_raw(repo, parents[0], ctx, modified, added, removed)
        else:
            p1 = parents[0]
            p2 = parents[1]

            modified_p1, added_p1, removed_p1 = [set(l) for l in repo.status(p1, ctx)[:3]]
            modified_p2, added_p2, removed_p2 = [set(l) for l in repo.status(p2, ctx)[:3]]

            added_both = added_p1 & added_p2
            modified_both = modified_p1 & modified_p2
            removed_both = removed_p1 & removed_p2

            combined_modified_p1 = modified_both | (modified_p1 & added_p2)
            combined_added_p1 = added_both | (added_p1 & modified_p2)
            combined_modified_p2 = modified_both | (modified_p2 & added_p1)
            combined_added_p2 = added_both | (added_p2 & modified_p1)

            combined_modified_p1 = really_differs(repo, p1, p2, ctx, combined_modified_p1)
            combined_added_p1 = really_differs(repo, p1, p2, ctx, combined_added_p1)
            combined_modified_p2 = really_differs(repo, p1, p2, ctx, combined_modified_p2)
            combined_added_p2 = really_differs(repo, p1, p2, ctx, combined_added_p2)

            _diff_git_raw(repo, p1, ctx, combined_modified_p1, combined_added_p1, removed_both)
            writeln('#@!_-=&')
            _diff_git_raw(repo, p2, ctx, combined_modified_p2, combined_added_p2, removed_both)

        i += 1
        if i == limit:
            break

def __dump_metadata(ctx):
        writeln('#@!_-=&')
        writeln(ctx.hex())
        writeln(str(ctx.rev()))
        writeln(ctx.branch())

        parents = ctx.parents()
        writeln(' '.join([str(p.hex()) for p in parents]))
        writeln(' '.join([str(p.rev()) for p in parents]))

        writeln(ctx.user())
        date = datestr(ctx.date(), format='%Y-%m-%d %H:%M:%S%z')
        writeln(date)

        description = encode(ctx.description())
        writeln(str(len(description)))
        write(description)

def __dump(repo, start, end):
    for rev in xrange(start, end):
        ctx = revsingle(repo, rev)

        __dump_metadata(ctx)
        parents = ctx.parents()

        modified, added, removed = repo.status(parents[0], ctx)[:3]
        writeln(str(len(modified)))
        writeln(str(len(added)))
        writeln(str(len(removed)))

        for filename in added + modified:
            fctx = ctx.filectx(filename)

            writeln(filename)
            writeln(' '.join(fctx.flags()))

            content = fctx.data()
            writeln(str(len(content)))
            sys.stdout.write(content)

        for filename in removed:
            writeln(filename)

def pretxnclose(ui, repo, **kwargs):
    start = revsingle(repo, kwargs['node'])
    end = revsingle(repo, kwargs['node_last'])
    __dump(repo, start.rev(), end.rev() + 1)

@command('dump', [],  'hg dump')
def dump(ui, repo, **opts):
    __dump(repo, 0, len(repo))

@command('metadata', [],  'hg metadata')
def dump(ui, repo, revs=None, **opts):
    if revs == None:
        revs = "0:tip"

    for r in revrange(repo, [revs]):
        ctx = repo[r]
        __dump_metadata(ctx)

@command('ls-tree', [],  'hg ls-tree')
def ls_tree(ui, repo, rev, **opts):
    nullHash = '0' * 40
    ctx = revsingle(repo, rev)
    for filename in ctx.manifest():
        fctx = ctx.filectx(filename)
        if 'x' in fctx.flags():
            write('100755 blob ')
        else:
            write('100644 blob ')
        write(nullHash)
        write('\t')
        writeln(filename)
