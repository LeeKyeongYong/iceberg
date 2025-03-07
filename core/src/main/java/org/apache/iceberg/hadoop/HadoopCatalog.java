/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.hadoop;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.iceberg.BaseMetastoreCatalog;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.LockManager;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.RuntimeIOException;
import org.apache.iceberg.io.CloseableGroup;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.relocated.com.google.common.base.Joiner;
import org.apache.iceberg.relocated.com.google.common.base.MoreObjects;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.base.Strings;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Sets;
import org.apache.iceberg.util.LocationUtil;
import org.apache.iceberg.util.LockManagers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HadoopCatalog provides a way to use table names like db.table to work with path-based tables
 * under a common location. It uses a specified directory under a specified filesystem as the
 * warehouse directory, and organizes multiple levels directories that mapped to the database,
 * namespace and the table respectively. The HadoopCatalog takes a location as the warehouse
 * directory. When creating a table such as $db.$tbl, it creates $db/$tbl directory under the
 * warehouse directory, and put the table metadata into that directory.
 *
 * <p>The HadoopCatalog now supports {@link org.apache.iceberg.catalog.Catalog#createTable}, {@link
 * org.apache.iceberg.catalog.Catalog#dropTable}, the {@link
 * org.apache.iceberg.catalog.Catalog#renameTable} is not supported yet.
 *
 * <p>Note: The HadoopCatalog requires that the underlying file system supports atomic rename.
 */
public class HadoopCatalog extends BaseMetastoreCatalog
    implements Closeable, SupportsNamespaces, Configurable {

  private static final Logger LOG = LoggerFactory.getLogger(HadoopCatalog.class);

  private static final String TABLE_METADATA_FILE_EXTENSION = ".metadata.json";
  private static final Joiner SLASH = Joiner.on("/");
  private static final PathFilter TABLE_FILTER =
      path -> path.getName().endsWith(TABLE_METADATA_FILE_EXTENSION);
  private static final String HADOOP_SUPPRESS_PERMISSION_ERROR = "suppress-permission-error";

  private String catalogName;
  private Configuration conf;
  private CloseableGroup closeableGroup;
  private String warehouseLocation;
  private FileSystem fs;
  private FileIO fileIO;
  private LockManager lockManager;
  private boolean suppressPermissionError = false;
  private Map<String, String> catalogProperties;

  public HadoopCatalog() {}

  @Override
  public void initialize(String name, Map<String, String> properties) {
    this.catalogProperties = ImmutableMap.copyOf(properties);
    String inputWarehouseLocation = properties.get(CatalogProperties.WAREHOUSE_LOCATION);
    Preconditions.checkArgument(
        !Strings.isNullOrEmpty(inputWarehouseLocation),
        "Cannot initialize HadoopCatalog because warehousePath must not be null or empty");

    this.catalogName = name;
    this.warehouseLocation = LocationUtil.stripTrailingSlash(inputWarehouseLocation);
    this.fs = Util.getFs(new Path(warehouseLocation), conf);

    String fileIOImpl =
        properties.getOrDefault(
            CatalogProperties.FILE_IO_IMPL, "org.apache.iceberg.hadoop.HadoopFileIO");

    this.fileIO = CatalogUtil.loadFileIO(fileIOImpl, properties, conf);

    this.lockManager = LockManagers.from(properties);

    this.closeableGroup = new CloseableGroup();
    closeableGroup.addCloseable(lockManager);
    closeableGroup.setSuppressCloseFailure(true);

    this.suppressPermissionError =
        Boolean.parseBoolean(properties.get(HADOOP_SUPPRESS_PERMISSION_ERROR));
  }

  /**
   * The constructor of the HadoopCatalog. It uses the passed location as its warehouse directory.
   *
   * @param conf The Hadoop configuration
   * @param warehouseLocation The location used as warehouse directory
   */
  public HadoopCatalog(Configuration conf, String warehouseLocation) {
    setConf(conf);
    initialize("hadoop", ImmutableMap.of(CatalogProperties.WAREHOUSE_LOCATION, warehouseLocation));
  }

  @Override
  public String name() {
    return catalogName;
  }

  private boolean shouldSuppressPermissionError(IOException ioException) {
    if (suppressPermissionError) {
      return ioException instanceof AccessDeniedException
          || (ioException.getMessage() != null
              && ioException.getMessage().contains("AuthorizationPermissionMismatch"));
    }
    return false;
  }

  private boolean isTableDir(Path path) {
    Path metadataPath = new Path(path, "metadata");
    // Only the path which contains metadata is the path for table, otherwise it could be
    // still a namespace.
    try {
      return fs.listStatus(metadataPath, TABLE_FILTER).length >= 1;
    } catch (FileNotFoundException e) {
      return false;
    } catch (IOException e) {
      if (shouldSuppressPermissionError(e)) {
        LOG.warn("Unable to list metadata directory {}", metadataPath, e);
        return false;
      } else {
        throw new UncheckedIOException(e);
      }
    }
  }

  private boolean isDirectory(Path path) {
    try {
      return fs.getFileStatus(path).isDirectory();
    } catch (FileNotFoundException e) {
      return false;
    } catch (IOException e) {
      if (shouldSuppressPermissionError(e)) {
        LOG.warn("Unable to list directory {}", path, e);
        return false;
      } else {
        throw new UncheckedIOException(e);
      }
    }
  }

  @Override
  public List<TableIdentifier> listTables(Namespace namespace) {
    Preconditions.checkArgument(
        namespace.levels().length >= 1, "Missing database in table identifier: %s", namespace);

    Path nsPath = new Path(warehouseLocation, SLASH.join(namespace.levels()));
    Set<TableIdentifier> tblIdents = Sets.newHashSet();

    try {
      if (!isDirectory(nsPath)) {
        throw new NoSuchNamespaceException("Namespace does not exist: %s", namespace);
      }
      RemoteIterator<FileStatus> it = fs.listStatusIterator(nsPath);
      while (it.hasNext()) {
        FileStatus status = it.next();
        if (!status.isDirectory()) {
          // Ignore the path which is not a directory.
          continue;
        }

        Path path = status.getPath();
        if (isTableDir(path)) {
          TableIdentifier tblIdent = TableIdentifier.of(namespace, path.getName());
          tblIdents.add(tblIdent);
        }
      }
    } catch (IOException ioe) {
      throw new RuntimeIOException(ioe, "Failed to list tables under: %s", namespace);
    }

    return Lists.newArrayList(tblIdents);
  }

  @Override
  protected boolean isValidIdentifier(TableIdentifier identifier) {
    return true;
  }

  @Override
  protected TableOperations newTableOps(TableIdentifier identifier) {
    return new HadoopTableOperations(
        new Path(defaultWarehouseLocation(identifier)), fileIO, conf, lockManager);
  }

  @Override
  protected String defaultWarehouseLocation(TableIdentifier tableIdentifier) {
    String tableName = tableIdentifier.name();
    StringBuilder sb = new StringBuilder();

    sb.append(warehouseLocation).append('/');
    for (String level : tableIdentifier.namespace().levels()) {
      sb.append(level).append('/');
    }
    sb.append(tableName);

    return sb.toString();
  }

  @Override
  public boolean dropTable(TableIdentifier identifier, boolean purge) {
    if (!isValidIdentifier(identifier)) {
      throw new NoSuchTableException("Invalid identifier: %s", identifier);
    }

    Path tablePath = new Path(defaultWarehouseLocation(identifier));
    TableOperations ops = newTableOps(identifier);
    TableMetadata lastMetadata = ops.current();
    try {
      if (lastMetadata == null) {
        LOG.debug("Not an iceberg table: {}", identifier);
        return false;
      } else {
        if (purge) {
          // Since the data files and the metadata files may store in different locations,
          // so it has to call dropTableData to force delete the data file.
          CatalogUtil.dropTableData(ops.io(), lastMetadata);
        }
        return fs.delete(tablePath, true /* recursive */);
      }
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Failed to delete file: %s", tablePath);
    }
  }

  @Override
  public void renameTable(TableIdentifier from, TableIdentifier to) {
    throw new UnsupportedOperationException("Cannot rename Hadoop tables");
  }

  @Override
  public void createNamespace(Namespace namespace, Map<String, String> meta) {
    Preconditions.checkArgument(
        !namespace.isEmpty(), "Cannot create namespace with invalid name: %s", namespace);
    if (!meta.isEmpty()) {
      throw new UnsupportedOperationException(
          "Cannot create namespace " + namespace + ": metadata is not supported");
    }

    Path nsPath = new Path(warehouseLocation, SLASH.join(namespace.levels()));

    if (isNamespace(nsPath)) {
      throw new AlreadyExistsException("Namespace already exists: %s", namespace);
    }

    try {
      fs.mkdirs(nsPath);

    } catch (IOException e) {
      throw new RuntimeIOException(e, "Create namespace failed: %s", namespace);
    }
  }

  @Override
  public List<Namespace> listNamespaces(Namespace namespace) {
    Path nsPath =
        namespace.isEmpty()
            ? new Path(warehouseLocation)
            : new Path(warehouseLocation, SLASH.join(namespace.levels()));
    if (!isNamespace(nsPath)) {
      throw new NoSuchNamespaceException("Namespace does not exist: %s", namespace);
    }

    try {
      // using the iterator listing allows for paged downloads
      // from HDFS and prefetching from object storage.
      List<Namespace> namespaces = Lists.newArrayList();
      RemoteIterator<FileStatus> it = fs.listStatusIterator(nsPath);
      while (it.hasNext()) {
        Path path = it.next().getPath();
        if (isNamespace(path)) {
          namespaces.add(append(namespace, path.getName()));
        }
      }
      return namespaces;
    } catch (IOException ioe) {
      throw new RuntimeIOException(ioe, "Failed to list namespace under: %s", namespace);
    }
  }

  private Namespace append(Namespace ns, String name) {
    String[] levels = Arrays.copyOfRange(ns.levels(), 0, ns.levels().length + 1);
    levels[ns.levels().length] = name;
    return Namespace.of(levels);
  }

  @Override
  public boolean dropNamespace(Namespace namespace) {
    Path nsPath = new Path(warehouseLocation, SLASH.join(namespace.levels()));

    if (!isNamespace(nsPath) || namespace.isEmpty()) {
      return false;
    }

    try {
      if (fs.listStatusIterator(nsPath).hasNext()) {
        throw new NamespaceNotEmptyException("Namespace %s is not empty.", namespace);
      }

      return fs.delete(nsPath, false /* recursive */);
    } catch (IOException e) {
      throw new RuntimeIOException(e, "Namespace delete failed: %s", namespace);
    }
  }

  @Override
  public boolean setProperties(Namespace namespace, Map<String, String> properties) {
    throw new UnsupportedOperationException(
        "Cannot set namespace properties " + namespace + " : setProperties is not supported");
  }

  @Override
  public boolean removeProperties(Namespace namespace, Set<String> properties) {
    throw new UnsupportedOperationException(
        "Cannot remove properties " + namespace + " : removeProperties is not supported");
  }

  @Override
  public Map<String, String> loadNamespaceMetadata(Namespace namespace) {
    Path nsPath = new Path(warehouseLocation, SLASH.join(namespace.levels()));

    if (!isNamespace(nsPath) || namespace.isEmpty()) {
      throw new NoSuchNamespaceException("Namespace does not exist: %s", namespace);
    }

    return ImmutableMap.of("location", nsPath.toString());
  }

  private boolean isNamespace(Path path) {
    return isDirectory(path) && !isTableDir(path);
  }

  @Override
  public void close() throws IOException {
    closeableGroup.close();
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("name", catalogName)
        .add("location", warehouseLocation)
        .toString();
  }

  @Override
  public TableBuilder buildTable(TableIdentifier identifier, Schema schema) {
    return new HadoopCatalogTableBuilder(identifier, schema);
  }

  @Override
  public void setConf(Configuration conf) {
    this.conf = conf;
  }

  @Override
  public Configuration getConf() {
    return conf;
  }

  @Override
  protected Map<String, String> properties() {
    return catalogProperties == null ? ImmutableMap.of() : catalogProperties;
  }

  private class HadoopCatalogTableBuilder extends BaseMetastoreCatalogTableBuilder {
    private final String defaultLocation;

    private HadoopCatalogTableBuilder(TableIdentifier identifier, Schema schema) {
      super(identifier, schema);
      defaultLocation = defaultWarehouseLocation(identifier);
    }

    @Override
    public TableBuilder withLocation(String location) {
      Preconditions.checkArgument(
          location == null || location.equals(defaultLocation),
          "Cannot set a custom location for a path-based table. Expected "
              + defaultLocation
              + " but got "
              + location);
      return this;
    }
  }
}
