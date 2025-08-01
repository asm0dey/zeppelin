/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zeppelin.notebook.repo;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.NameScope;
import org.apache.commons.vfs2.Selectors;
import org.apache.commons.vfs2.VFS;
import org.apache.zeppelin.conf.ZeppelinConfiguration;
import org.apache.zeppelin.conf.ZeppelinConfiguration.ConfVars;
import org.apache.zeppelin.notebook.Note;
import org.apache.zeppelin.notebook.NoteInfo;
import org.apache.zeppelin.notebook.NoteParser;
import org.apache.zeppelin.notebook.exception.CorruptedNoteException;
import org.apache.zeppelin.user.AuthenticationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
* NotebookRepo implementation based on apache vfs
*/
public class VFSNotebookRepo extends AbstractNotebookRepo {
  private static final Logger LOGGER = LoggerFactory.getLogger(VFSNotebookRepo.class);

  protected FileSystemManager fsManager;
  protected FileObject rootNotebookFileObject;
  protected String rootNotebookFolder;

  public VFSNotebookRepo() {

  }

  @Override
  public void init(ZeppelinConfiguration zConf, NoteParser noteParser) throws IOException {
    super.init(zConf, noteParser);
    setNotebookDirectory(zConf.getNotebookDir());
  }

  protected void setNotebookDirectory(String notebookDirPath) throws IOException {
    URI filesystemRoot = null;
    try {
      LOGGER.info("Using notebookDir: {}", notebookDirPath);
      if (zConf.isWindowsPath(notebookDirPath)) {
        filesystemRoot = new File(notebookDirPath).toURI();
      } else {
        filesystemRoot = new URI(notebookDirPath);
      }
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }

    if (filesystemRoot.getScheme() == null) { // it is local path
      File f = new File(zConf.getAbsoluteDir(filesystemRoot.getPath()));
      filesystemRoot = f.toURI();
    }
    this.fsManager = VFS.getManager();
    this.rootNotebookFileObject = fsManager.resolveFile(filesystemRoot);
    if (!this.rootNotebookFileObject.exists()) {
      this.rootNotebookFileObject.createFolder();
      LOGGER.info("Notebook dir doesn't exist: {}, creating it.",
          rootNotebookFileObject.getName().getPath());
    }
    // getPath() method returns a string without root directory in windows, so we use getURI() instead
    // windows does not support paths with "file:///" prepended, so we replace it by "/"
    this.rootNotebookFolder = rootNotebookFileObject.getName().getURI().replace("file:///", "/");
  }

  @Override
  public Map<String, NoteInfo> list(AuthenticationInfo subject) throws IOException {
    // Must to create rootNotebookFileObject each time when call method list, otherwise we can not
    // get the updated data under this folder.
    this.rootNotebookFileObject = fsManager.resolveFile(this.rootNotebookFolder);
    return listFolder(rootNotebookFileObject);
  }

  private Map<String, NoteInfo> listFolder(FileObject fileObject) throws IOException {
    Map<String, NoteInfo> noteInfos = new HashMap<>();

    if (fileObject.getName().getBaseName().startsWith(".")) {
      LOGGER.warn("Skip hidden item: {}", fileObject.getName());
      return noteInfos;
    }

    if (fileObject.isFolder()) {
      for (FileObject child : fileObject.getChildren()) {
        noteInfos.putAll(listFolder(child));
      }
    } else {
      // getPath() drops the drive on Windows, so use getURI().
      // Decode URI to change %20 to spaces.
      // Windows cannot handle "file:///", replace it with "/".
      String noteFileName = URLDecoder.decode(
              fileObject.getName().getURI(), StandardCharsets.UTF_8
      ).replace("file:///", "/");

      if (noteFileName.endsWith(".zpln")) {
        try {
          String noteId = getNoteId(noteFileName);
          String notePath = getNotePath(rootNotebookFolder, noteFileName);
          noteInfos.put(noteId, new NoteInfo(noteId, notePath));
        } catch (IOException e) {
          LOGGER.warn(e.getMessage());
        }
      }
    }
    return noteInfos;
  }

  @Override
  public Note get(String noteId, String notePath, AuthenticationInfo subject)
      throws IOException {
    FileObject noteFile = rootNotebookFileObject.resolveFile(buildNoteFileName(noteId, notePath),
        NameScope.DESCENDENT);
    String json = IOUtils.toString(noteFile.getContent().getInputStream(),
        zConf.getString(ConfVars.ZEPPELIN_ENCODING));

    try {
      Note note = noteParser.fromJson(noteId, json);
      // setPath here just for testing, because actually NoteManager will setPath
      note.setPath(notePath);
      return note;
    } catch (CorruptedNoteException e) {
      String errorMessage = String.format(
          "Fail to parse note json. Please check the file at this path to resolve the issue. "
          + "Path: %s, "
          + "Content: %s",
          rootNotebookFolder + notePath, json
      );
      throw new CorruptedNoteException(noteId, errorMessage, e);
    }
  }

  @Override
  public synchronized void save(Note note, AuthenticationInfo subject) throws IOException {
    LOGGER.info("Saving note {} to {}", note.getId(), buildNoteFileName(note));
    // write to tmp file first, then rename it to the {note_name}_{note_id}.zpln
    FileObject noteJson = rootNotebookFileObject.resolveFile(
        buildNoteTempFileName(note), NameScope.DESCENDENT);
    OutputStream out = null;
    try {
      out = noteJson.getContent().getOutputStream(false);
      IOUtils.write(note.toJson().getBytes(zConf.getString(ConfVars.ZEPPELIN_ENCODING)), out);
    } finally {
      if (out != null) {
        out.close();
      }
    }
    noteJson.moveTo(rootNotebookFileObject.resolveFile(
        buildNoteFileName(note), NameScope.DESCENDENT));
  }

  @Override
  public void move(String noteId,
                   String notePath,
                   String newNotePath,
                   AuthenticationInfo subject) throws IOException {
    LOGGER.info("Move note {} from {} to {}", noteId, notePath, newNotePath);
    FileObject fileObject = rootNotebookFileObject.resolveFile(
        buildNoteFileName(noteId, notePath), NameScope.DESCENDENT);
    FileObject destFileObject = rootNotebookFileObject.resolveFile(
        buildNoteFileName(noteId, newNotePath), NameScope.DESCENDENT);
    // create parent folder first, otherwise move operation will fail
    destFileObject.getParent().createFolder();
    fileObject.moveTo(destFileObject);
  }

  @Override
  public void move(String folderPath, String newFolderPath,
                   AuthenticationInfo subject) throws IOException{
    LOGGER.info("Move folder from {} to {}", folderPath, newFolderPath);
    FileObject fileObject = rootNotebookFileObject.resolveFile(
        folderPath.substring(1), NameScope.DESCENDENT);
    FileObject destFileObject = rootNotebookFileObject.resolveFile(
        newFolderPath.substring(1), NameScope.DESCENDENT);
    // create parent folder first, otherwise move operation will fail
    destFileObject.getParent().createFolder();
    fileObject.moveTo(destFileObject);
  }

  @Override
  public void remove(String noteId, String notePath, AuthenticationInfo subject)
      throws IOException {
    LOGGER.info("Remove note: {}, notePath: {}", noteId, notePath);
    FileObject noteFile = rootNotebookFileObject.resolveFile(
        buildNoteFileName(noteId, notePath), NameScope.DESCENDENT);
    noteFile.delete(Selectors.SELECT_SELF);
  }

  @Override
  public void remove(String folderPath, AuthenticationInfo subject) throws IOException {
    LOGGER.info("Remove folder: {}", folderPath);
    FileObject folderObject = rootNotebookFileObject.resolveFile(
        folderPath.substring(1), NameScope.DESCENDENT);
    folderObject.deleteAll();
  }

  @Override
  public void close() {
    //no-op
  }

  @Override
  public List<NotebookRepoSettingsInfo> getSettings(AuthenticationInfo subject) {
    NotebookRepoSettingsInfo repoSetting = NotebookRepoSettingsInfo.newInstance();
    List<NotebookRepoSettingsInfo> settings = new ArrayList<>();
    repoSetting.name = "Notebook Path";
    repoSetting.type = NotebookRepoSettingsInfo.Type.INPUT;
    repoSetting.value = Collections.emptyList();
    repoSetting.selected = rootNotebookFileObject.getName().getPath();

    settings.add(repoSetting);
    return settings;
  }

  @Override
  public void updateSettings(Map<String, String> settings, AuthenticationInfo subject) {
    if (settings == null || settings.isEmpty()) {
      LOGGER.error("Cannot update {} with empty settings", this.getClass().getName());
      return;
    }
    String newNotebookDirectotyPath = StringUtils.EMPTY;
    if (settings.containsKey("Notebook Path")) {
      newNotebookDirectotyPath = settings.get("Notebook Path");
    }

    if (StringUtils.isBlank(newNotebookDirectotyPath)) {
      LOGGER.error("Notebook path is invalid");
      return;
    }
    LOGGER.warn("{} will change notebook dir from {} to {}",
        subject.getUser(), this.rootNotebookFolder, newNotebookDirectotyPath);
    try {
      setNotebookDirectory(newNotebookDirectotyPath);
    } catch (IOException e) {
      LOGGER.error("Cannot update notebook directory", e);
    }
  }
}

