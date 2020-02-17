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
import mercurial.hg
import mercurial.node
import mercurial.copies
import difflib
import sys

# space separated version list
testedwith = '4.9.2 5.0.2 5.2.1'

def mode(fctx):
    flags = fctx.flags()
    if flags == b'': return b'100644'
    if flags == b'x': return b'100755'
    if flags == b'l': return b'120000'

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

def write(s):
    if sys.version_info >= (3, 0):
        sys.stdout.buffer.write(s)
    else:
        sys.stdout.write(s)

def writeln(s):
    write(s)
    write(b'\n')

def int_to_str(i):
    return str(i).encode('ascii')

def _match_exact(root, cwd, files, badfn=None):
    """
    Wrapper for mercurial.match.exact that ignores some arguments based on the used version
    """
    if mercurial.util.version().startswith(b"5"):
        return mercurial.match.exact(files, badfn)
    else:
        return mercurial.match.exact(root, cwd, files, badfn)

def _diff_git_raw(repo, ctx1, ctx2, modified, added, removed, showPatch):
    nullHash = b'0' * 40
    removed_copy = set(removed)

    copied = mercurial.copies.pathcopies(ctx1, ctx2)

    for path in added:
        fctx = ctx2.filectx(path)
        if fctx.renamed():
            old_path, _ = fctx.renamed()
            if old_path in removed:
                removed_copy.discard(old_path)
        elif path in copied:
            old_path = copied[path]
            if old_path in removed:
                removed_copy.discard(old_path)

    for path in sorted(modified | added | removed_copy):
        if path in modified:
            fctx = ctx2.filectx(path)
            writeln(b':' + mode(ctx1.filectx(path)) + b' ' + mode(fctx) + b' ' + nullHash + b' ' + nullHash + b' M\t' + fctx.path())
        elif path in added:
            fctx = ctx2.filectx(path)
            if fctx.renamed():
                parent = fctx.p1()
                score = int_to_str(int(ratio(parent.data(), fctx.data(), 0.5) * 100))
                old_path, _ = fctx.renamed()

                if old_path in removed:
                    operation = b'R'
                else:
                    operation = b'C'

                write(b':' + mode(parent) + b' ' + mode(fctx) + b' ' + nullHash + b' ' + nullHash + b' ')
                writeln(operation + score + b'\t' + old_path + b'\t' + path)
            elif path in copied:
                old_path = copied[path]
                score = b'100'

                if old_path in removed:
                    operation = b'R'
                else:
                    operation = b'C'

                write(b':' + mode(fctx) + b' ' + mode(fctx) + b' ' + nullHash + b' ' + nullHash + b' ')
                writeln(operation + score + b'\t' + old_path + b'\t' + path)
            else:
                writeln(b':000000 ' + mode(fctx) + b' ' + nullHash + b' ' + nullHash + b' A\t' + fctx.path())
        elif path in removed_copy:
            fctx = ctx1.filectx(path)
            writeln(b':' + mode(fctx) + b' 000000 ' + nullHash + b' ' + nullHash + b' D\t' + path)

    if showPatch:
        writeln(b'')

        match = _match_exact(repo.root, repo.getcwd(), list(modified) + list(added) + list(removed_copy))
        opts = mercurial.mdiff.diffopts(git=True, nodates=True, context=0)
        for d in mercurial.patch.diff(repo, ctx1.node(), ctx2.node(), match=match, opts=opts):
            write(d)

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

@command(b'diff-git-raw', [(b'', b'patch', False, b''), (b'', b'files', b'', b'')], b'hg diff-git-raw rev1 [rev2]')
def diff_git_raw(ui, repo, rev1, rev2=None, *files, **opts):
    ctx1 = revsingle(repo, rev1)

    if rev2 != None:
        ctx2 = revsingle(repo, rev2)
        status = repo.status(ctx1, ctx2)
    else:
        ctx2 = mercurial.context.workingctx(repo)
        status = repo.status(ctx1)

    modified, added, removed = [set(l) for l in status[:3]]

    files = opts['files']
    if files != b'':
        wanted = set(files.split(b','))
        modified = modified & wanted
        added = added & wanted
        removed = removed & wanted

    _diff_git_raw(repo, ctx1, ctx2, modified, added, removed, opts['patch'])

@command(b'log-git', [(b'', b'reverse', False, b''), (b'l', b'limit', -1, b'')],  b'hg log-git <revisions>')
def log_git(ui, repo, revs=None, **opts):
    if len(repo) == 0:
        return

    if revs == None:
        if opts['reverse']:
            revs = b'0:tip'
        else:
            revs = b'tip:0'

    limit = opts['limit']
    i = 0
    for r in revrange(repo, [revs]):
        ctx = repo[r]

        __dump_metadata(ctx)
        parents = ctx.parents()

        if len(parents) == 1:
            modified, added, removed = [set(l) for l in repo.status(parents[0], ctx)[:3]]
            _diff_git_raw(repo, parents[0], ctx, modified, added, removed, True)
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

            _diff_git_raw(repo, p1, ctx, combined_modified_p1, combined_added_p1, removed_both, True)
            writeln(b'#@!_-=&')
            _diff_git_raw(repo, p2, ctx, combined_modified_p2, combined_added_p2, removed_both, True)

        i += 1
        if i == limit:
            break

def __dump_metadata(ctx):
        writeln(b'#@!_-=&')
        writeln(ctx.hex())
        writeln(int_to_str(ctx.rev()))
        writeln(ctx.branch())

        parents = ctx.parents()
        writeln(b' '.join([p.hex() for p in parents]))
        writeln(b' '.join([int_to_str(p.rev()) for p in parents]))

        writeln(ctx.user())
        date = datestr(ctx.date(), format=b'%Y-%m-%d %H:%M:%S%z')
        writeln(date)

        description = ctx.description()
        writeln(int_to_str(len(description)))
        write(description)

def __dump(repo, start, end):
    for rev in range(start, end):
        ctx = revsingle(repo, rev)

        __dump_metadata(ctx)
        parents = ctx.parents()

        modified, added, removed = repo.status(parents[0], ctx)[:3]
        writeln(int_to_str(len(modified)))
        writeln(int_to_str(len(added)))
        writeln(int_to_str(len(removed)))

        for filename in added + modified:
            fctx = ctx.filectx(filename)

            writeln(filename)
            writeln(b' '.join(fctx.flags()))

            content = fctx.data()
            writeln(int_to_str(len(content)))
            write(content)

        for filename in removed:
            writeln(filename)

def pretxnclose(ui, repo, **kwargs):
    start = revsingle(repo, kwargs['node'])
    end = revsingle(repo, kwargs['node_last'])
    __dump(repo, start.rev(), end.rev() + 1)

@command(b'dump', [], b'hg dump')
def dump(ui, repo, **opts):
    __dump(repo, 0, len(repo))

@command(b'metadata', [], b'hg metadata')
def metadata(ui, repo, revs, filenames=None, **opts):
    if filenames != None:
        fnames = filenames.split(b"\t")

    for r in revrange(repo, [revs]):
        ctx = repo[r]
        if filenames == None:
            __dump_metadata(ctx)
        else:
            modified, added, removed = ctx.status(ctx.p1(), _match_exact(repo.root, repo.getcwd(), fnames))[:3]
            if modified or added or removed:
                __dump_metadata(ctx)

@command(b'ls-tree', [], b'hg ls-tree')
def ls_tree(ui, repo, rev, **opts):
    nullHash = b'0' * 40
    ctx = revsingle(repo, rev)
    for filename in ctx.manifest():
        fctx = ctx.filectx(filename)
        if b'x' in fctx.flags():
            write(b'100755 blob ')
        else:
            write(b'100644 blob ')
        write(nullHash)
        write(b'\t')
        writeln(filename)

@command(b'ls-remote', [], b'hg ls-remote PATH')
def ls_remote(ui, repo, path, **opts):
    peer = mercurial.hg.peer(ui or repo, opts, ui.expandpath(path))
    for branch, heads in peer.branchmap().iteritems():
        for head in heads:
            write(mercurial.node.hex(head))
            write(b"\t")
            writeln(branch)
