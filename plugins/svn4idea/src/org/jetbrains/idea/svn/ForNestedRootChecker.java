/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.idea.svn;

import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.vcs.impl.VcsRootIterator;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.tmatesoft.svn.core.SVNErrorCode;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.wc.SVNInfo;
import org.tmatesoft.svn.core.wc.SVNRevision;

import java.io.File;
import java.util.LinkedList;
import java.util.List;

public class ForNestedRootChecker {

  @NotNull private final SvnVcs myVcs;
  @NotNull private final VcsRootIterator myRootIterator;

  public ForNestedRootChecker(@NotNull SvnVcs vcs) {
    myVcs = vcs;
    myRootIterator = new VcsRootIterator(vcs.getProject(), vcs);
  }

  @NotNull
  public List<Node> getAllNestedWorkingCopies(@NotNull VirtualFile root) {
    LinkedList<Node> result = ContainerUtil.newLinkedList();
    LinkedList<VirtualFile> workItems = ContainerUtil.newLinkedList();

    workItems.add(root);
    while (!workItems.isEmpty()) {
      VirtualFile item = workItems.removeFirst();
      checkCancelled();

      final Node vcsElement = new VcsFileResolver(myVcs, item).resolve();
      if (vcsElement != null) {
        result.add(vcsElement);
      }
      else if (item.isDirectory() && !SvnUtil.isAdminDirectory(item)) {
        // TODO: Only directory children should be checked.
        for (VirtualFile child : item.getChildren()) {
          checkCancelled();

          if (myRootIterator.acceptFolderUnderVcs(root, child)) {
            workItems.add(child);
          }
        }
      }
    }
    return result;
  }

  private void checkCancelled() {
    if (myVcs.getProject().isDisposed()) {
      throw new ProcessCanceledException();
    }
  }

  private static class VcsFileResolver {

    @NotNull private final SvnVcs myVcs;
    @NotNull private final VirtualFile myFile;
    @NotNull private final File myIoFile;
    @Nullable private SVNInfo myInfo;
    @Nullable private SVNException myError;

    private VcsFileResolver(@NotNull SvnVcs vcs, @NotNull VirtualFile file) {
      myVcs = vcs;
      myFile = file;
      myIoFile = VfsUtilCore.virtualToIoFile(file);
    }

    @Nullable
    public Node resolve() {
      runInfo();

      return processInfo();
    }

    private void runInfo() {
      try {
        myInfo = myVcs.getFactory(myIoFile, false).createInfoClient().doInfo(myIoFile, SVNRevision.UNDEFINED);
      }
      catch (SVNException e) {
        myError = e;
      }
    }

    @Nullable
    private Node processInfo() {
      Node result = null;

      if (myError != null) {
        SVNErrorCode errorCode = myError.getErrorMessage().getErrorCode();

        if (!SvnUtil.isUnversionedOrNotFound(errorCode)) {
          // error code does not indicate that myFile is unversioned or path is invalid => create result, but indicate error
          result = new Node(myFile, getFakeUrl(), getFakeUrl(), myError);
        }
      }
      else if (myInfo != null && myInfo.getRepositoryRootURL() != null && myInfo.getURL() != null) {
        result = new Node(myFile, myInfo.getURL(), myInfo.getRepositoryRootURL());
      }

      return result;
    }

    // TODO: This should be updated when fully removing SVNKit object model from code
    private SVNURL getFakeUrl() {
      // do not have constants like SVNURL.EMPTY - use fake url - it should be never used in any real logic
      try {
        return SVNURL.fromFile(myIoFile);
      }
      catch (SVNException e) {
        // should not occur
        throw SvnUtil.createIllegalArgument(e);
      }
    }
  }
}
