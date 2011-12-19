/*
 * This program is free software; you can redistribute it and/or modify it under the
 * terms of the GNU General Public License, version 2 as published by the Free Software
 * Foundation.
 *
 * You should have received a copy of the GNU General Public License along with this
 * program; if not, you can obtain a copy at http://www.gnu.org/licenses/gpl-2.0.html
 * or from the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * Copyright 2011 Pentaho Corporation. All rights reserved.
 */
package org.pentaho.platform.repository2.unified.importexport;

import org.apache.commons.collections.map.UnmodifiableMap;
import org.apache.commons.collections.set.UnmodifiableSet;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.pentaho.platform.api.engine.IPluginManager;
import org.pentaho.platform.api.repository2.unified.IRepositoryFileData;
import org.pentaho.platform.api.repository2.unified.IUnifiedRepository;
import org.pentaho.platform.api.repository2.unified.RepositoryFile;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.pentaho.platform.repository.RepositoryFilenameUtils;
import org.pentaho.platform.repository2.messages.Messages;
import org.springframework.util.Assert;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Class Description
 *
 * @author <a href="mailto:dkincade@pentaho.com">David M. Kincade</a>
 */
public class DefaultImportHandler implements ImportHandler {
  private static final Log log = LogFactory.getLog(DefaultImportHandler.class);
  private static final Messages messages = Messages.getInstance();
  private static final Map<String, Converter> converters = new HashMap<String, Converter>();

  private final IUnifiedRepository repository;
  private Set<String> executableTypes;
  private HashMap<String, Serializable> parentIdCache;

  static {
    final StreamConverter streamConverter = new StreamConverter();
    converters.put("prpt", streamConverter);
    converters.put("mondrian.xml", streamConverter);
    converters.put("kjb", streamConverter);
    converters.put("ktr", streamConverter);
    converters.put("report", streamConverter);
    converters.put("rptdesign", streamConverter);
    converters.put("svg", streamConverter);
    converters.put("url", streamConverter);
    converters.put("xaction", streamConverter);
    converters.put("xanalyzer", streamConverter);
    converters.put("xcdf", streamConverter);
    converters.put("xdash", streamConverter);
    converters.put("xreportspec", streamConverter);
    converters.put("waqr.xaction", streamConverter);
    converters.put("xwaqr", streamConverter);
    converters.put("gif", streamConverter);
    converters.put("css", streamConverter);
    converters.put("html", streamConverter);
    converters.put("htm", streamConverter);
    converters.put("jpg", streamConverter);
    converters.put("jpeg", streamConverter);
    converters.put("js", streamConverter);
    converters.put("cfg.xml", streamConverter);
    converters.put("jrxml", streamConverter);
    converters.put("png", streamConverter);
    converters.put("properties", streamConverter);
    converters.put("sql", streamConverter);
    converters.put("xmi", streamConverter);
    converters.put("xml", streamConverter);
  }


  public DefaultImportHandler(final IUnifiedRepository repository) {
    // Validate and save the repository
    if (null == repository) {
      throw new IllegalArgumentException();
    }
    this.repository = repository;

    // Determine the executable types (these will be the only types made visible in the repository)
    determineExecutableTypes();

    // Setup the parent ID cache
    parentIdCache = new HashMap<String, Serializable>();
  }

  /**
   * Returns the name of this Import Handler
   */
  @Override
  public String getName() {
    return "DefaultImportHandler";
  }

  protected static Map<String, Converter> getConverters() {
    return UnmodifiableMap.decorate(converters);
  }

  protected Set<String> getExecutableTypes() {
    return UnmodifiableSet.decorate(executableTypes);
  }

  protected Map<String, Serializable> getParentIdCache() {
    return UnmodifiableMap.decorate(parentIdCache);
  }

  /**
   * Processes the list of files and performs any processing required to import that data into the repository. If
   * during processing it handles file(s) which should not be handled by downstream import handlers, then it
   * should remove them from the set of files provided.
   *
   * @param importFileSet the set of files to be imported - any files handled to completion by this Import Handler
   *                      should remove this files from this list
   * @param comment       the import comment provided
   * @param overwrite     indicates if the process is authorized to overwrite existing content in the repository
   * @throws ImportException indicates a significant error during import processing
   */
  @Override
  public void doImport(final Iterable<ImportSource.IRepositoryFileBundle> importFileSet, final String destinationPath,
                       final String comment, final boolean overwrite) throws ImportException {
    if (null == importFileSet || StringUtils.isEmpty(destinationPath)) {
      throw new IllegalArgumentException();
    }

    // Iterate through the file set
    for (Iterator iterator = importFileSet.iterator(); iterator.hasNext(); ) {
      final ImportSource.IRepositoryFileBundle bundle = (ImportSource.IRepositoryFileBundle) iterator.next();

      // We should ignore anything in the "system" or "admin" folders
      final String bundlePath = computeBundlePath(bundle);
      if (isSystemPath(bundlePath)) {
        log.trace("Skipping " + bundlePath + " since it is in admin / system folders");
        continue;
      }
      final String bundlePathName = RepositoryFilenameUtils.concat(bundlePath, bundle.getFile().getName());
      final String repositoryFilePath = RepositoryFilenameUtils.concat(destinationPath, bundlePathName);

      // See if the destination already exists in the repository
      final RepositoryFile file = repository.getFile(repositoryFilePath);
      if (file != null) {
        if (!overwrite) {
          log.trace("File already exists in repository and overwrite is false - skip");
        } else if (file.isFolder()) {
          log.trace("File is a folder that already exists - skip");
        } else {
          // It is a file we can overwrite...
          copyFileToRepository(bundle, destinationPath, bundlePath, bundlePathName, file, comment);
        }
        // We handled this file (even if by doing nothing)
        iterator.remove();
        continue;
      }

      // The file doesn't exist - if it is a folder then this is easy
      if (bundle.getFile().isFolder()) {
        log.trace("creating folder " + bundlePath);
        if (bundle.getAcl() != null) {
          repository.createFolder(getParentId(destinationPath, bundlePath), bundle.getFile(), bundle.getAcl(), comment);
        } else {
          repository.createFolder(getParentId(destinationPath, bundlePath), bundle.getFile(), comment);
        }
        iterator.remove();
      } else {
        // It is a file ...
        if (copyFileToRepository(bundle, destinationPath, bundlePath, bundlePathName, null, comment)) {
          iterator.remove();
        }
      }
    }
  }

  /**
   * Detects if the 1st or 2nd part of the path are "reserved" directories (such as 'system' or 'admin')
   */
  protected static boolean isSystemPath(final String bundlePath) {
    final String[] split = StringUtils.split(bundlePath, RepositoryFile.SEPARATOR);
    return isSystemDir(split, 0) || isSystemDir(split, 1);
  }

  protected static boolean isSystemDir(final String[] split, final int index) {
    return (split != null && index < split.length &&
        (StringUtils.equals(split[index], "system") || StringUtils.equals(split[index], "admin")));
  }

  /**
   * Copies the file bundle into the repository
   *
   * @param bundle
   * @param bundlePath
   * @param bundlePathName
   * @param file
   * @param comment
   */
  protected boolean copyFileToRepository(final ImportSource.IRepositoryFileBundle bundle, final String destinationPath,
                                         final String bundlePath, final String bundlePathName,
                                         final RepositoryFile file, final String comment) {
    // Compute the file extension
    final String name = bundle.getFile().getName();
    final String ext = RepositoryFilenameUtils.getExtension(name);
    if (StringUtils.isEmpty(ext)) {
      log.debug("Skipping file without extension: " + bundlePathName);
      return false;
    }

    // Check the mime type
    final String mimeType = bundle.getMimeType();
    if (mimeType == null) {
      log.debug("Skipping file without mime-type: " + bundlePathName);
      return false;
    }

    // Find the converter
    final Converter converter = converters.get(ext);
    if (converter == null) {
      log.debug("Skipping file without converter: " + bundlePathName);
      return false;
    }

    // Copy the file into the repository
    try {
      log.trace("copying file to repository: " + bundlePathName);
      IRepositoryFileData data = converter.convert(bundle.getInputStream(), bundle.getCharset(), mimeType);
      if (null == file) {
        createFile(bundle, destinationPath, bundlePath, ext, data, comment);
      } else {
        repository.updateFile(file, data, comment);
      }
      return true;
    } catch (IOException e) {
      log.warn(messages.getString("DefaultImportHandler.WARN_0003_IOEXCEPTION", name), e); // TODO make sure string exists
      return false;
    }
  }

  /**
   * Creates a new file in the repository
   *
   * @param bundle
   * @param destinationPath
   * @param bundlePath
   * @param ext
   * @param data
   * @param comment
   */
  protected RepositoryFile createFile(final ImportSource.IRepositoryFileBundle bundle, final String destinationPath,
                                      final String bundlePath, final String ext,
                                      final IRepositoryFileData data, final String comment) {

    final boolean hidden = !executableTypes.contains(ext.toLowerCase());
    log.trace("\tsetting hidden=" + hidden + " for file with extension " + ext.toLowerCase());
    final RepositoryFile file = new RepositoryFile.Builder(bundle.getFile()).hidden(hidden).build();
    final Serializable parentId = getParentId(destinationPath, bundlePath);
    return repository.createFile(parentId, file, data, bundle.getAcl(), comment);
  }

  /**
   * Computes the bundle path from the bundle
   *
   * @param bundle
   * @return
   */
  protected String computeBundlePath(final ImportSource.IRepositoryFileBundle bundle) {
    String bundlePath = bundle.getPath();
    bundlePath = RepositoryFilenameUtils.separatorsToRepository(bundlePath);
    if (bundlePath.startsWith(RepositoryFile.SEPARATOR)) {
      bundlePath = bundlePath.substring(1);
    }
    return bundlePath;
  }

  /**
   * Gets (possibly from cache) id of parent folder of file pointed to by childPath.
   */
  protected Serializable getParentId(final String destFolderPath, final String childPath) {
    Assert.notNull(destFolderPath);
    Assert.notNull(childPath);
    Assert.notNull(parentIdCache);
    String normalizedChildPath = childPath;
    if (!childPath.startsWith(RepositoryFile.SEPARATOR)) {
      normalizedChildPath = RepositoryFile.SEPARATOR + childPath;
    }
    if (!parentIdCache.containsKey(normalizedChildPath)) {
      // get path to parent from child path
      String parentPath;
      int lastSlash = normalizedChildPath.lastIndexOf('/');
      if (lastSlash > 0) {
        parentPath = normalizedChildPath.substring(0, lastSlash);
      } else {
        parentPath = "";
      }
      if (parentPath.startsWith("/")) {
        parentPath = parentPath.substring(1);
      }

      // get id of parent from parent path
      RepositoryFile parentFile = repository.getFile(RepositoryFilenameUtils.concat(destFolderPath, parentPath));
      Assert.notNull(parentFile);
      parentIdCache.put(normalizedChildPath, parentFile.getId());
    }
    return parentIdCache.get(normalizedChildPath);
  }

  protected void determineExecutableTypes() {
    IPluginManager pluginManager = null;
    try {
      pluginManager = PentahoSystem.get(IPluginManager.class, null);
    } catch (Exception ignored) {
      log.debug("Executing outside the BIPLATFORM");
    }
    executableTypes = new HashSet<String>();
    if (pluginManager != null && pluginManager.getContentTypes() != null) {
      executableTypes.addAll(pluginManager.getContentTypes());
    }
    // Add the non-plugin types
    executableTypes.add("xaction");
    executableTypes.add("url");
  }

}
