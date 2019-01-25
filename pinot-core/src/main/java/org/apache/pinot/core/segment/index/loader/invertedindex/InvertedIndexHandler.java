/**
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
package org.apache.pinot.core.segment.index.loader.invertedindex;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nonnull;
import org.apache.commons.io.FileUtils;
import org.apache.pinot.common.data.PinotObject;
import org.apache.pinot.common.data.objects.JSONObject;
import org.apache.pinot.common.data.objects.MapObject;
import org.apache.pinot.common.data.objects.TextObject;
import org.apache.pinot.common.utils.TarGzCompressionUtils;
import org.apache.pinot.core.indexsegment.generator.SegmentVersion;
import org.apache.pinot.core.io.reader.DataFileReader;
import org.apache.pinot.core.io.reader.SingleColumnMultiValueReader;
import org.apache.pinot.core.io.reader.impl.v1.FixedBitMultiValueReader;
import org.apache.pinot.core.io.reader.impl.v1.FixedBitSingleValueReader;
import org.apache.pinot.core.io.reader.impl.v1.VarByteChunkSingleValueReader;
import org.apache.pinot.core.segment.creator.impl.V1Constants;
import org.apache.pinot.core.segment.creator.impl.inv.LuceneIndexCreator;
import org.apache.pinot.core.segment.creator.impl.inv.OffHeapBitmapInvertedIndexCreator;
import org.apache.pinot.core.segment.index.ColumnMetadata;
import org.apache.pinot.core.segment.index.SegmentMetadataImpl;
import org.apache.pinot.core.segment.index.loader.IndexLoadingConfig;
import org.apache.pinot.core.segment.index.loader.LoaderUtils;
import org.apache.pinot.core.segment.memory.PinotDataBuffer;
import org.apache.pinot.core.segment.store.ColumnIndexType;
import org.apache.pinot.core.segment.store.SegmentDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class InvertedIndexHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(InvertedIndexHandler.class);

  private final File _indexDir;
  private final SegmentDirectory.Writer _segmentWriter;
  private final String _segmentName;
  private final SegmentVersion _segmentVersion;
  private final Set<ColumnMetadata> _invertedIndexColumns = new HashSet<>();

  public InvertedIndexHandler(@Nonnull File indexDir, @Nonnull SegmentMetadataImpl segmentMetadata,
      @Nonnull IndexLoadingConfig indexLoadingConfig, @Nonnull SegmentDirectory.Writer segmentWriter) {
    _indexDir = indexDir;
    _segmentWriter = segmentWriter;
    _segmentName = segmentMetadata.getName();
    _segmentVersion = SegmentVersion.valueOf(segmentMetadata.getVersion());

    // Do not create inverted index for sorted column
    for (String column : indexLoadingConfig.getInvertedIndexColumns()) {
      ColumnMetadata columnMetadata = segmentMetadata.getColumnMetadataFor(column);
      if (columnMetadata != null && !columnMetadata.isSorted()) {
        _invertedIndexColumns.add(columnMetadata);
      }
    }
  }

  public void createInvertedIndices()
      throws IOException {
    for (ColumnMetadata columnMetadata : _invertedIndexColumns) {
      String objectType = columnMetadata.getObjectType();
      if (objectType == null) {
        createInvertedIndexForSimpleField(columnMetadata);
      } else {
        createInvertedIndexForComplexObject(columnMetadata);
      }
    }
  }

  @SuppressWarnings("unchecked")
  private void createInvertedIndexForComplexObject(ColumnMetadata columnMetadata)
      throws IOException {
    String column = columnMetadata.getColumnName();

    File inProgress = new File(_indexDir, column + ".inv.inprogress");
    File invertedIndexDir = new File(_indexDir, column + V1Constants.Indexes.LUCENE_INVERTED_INDEX_DIR);

    if (!inProgress.exists()) {
      // Marker file does not exist, which means last run ended normally.

      if (invertedIndexDir.exists()) {
        // Skip creating inverted index if already exists.
        LOGGER.info("Found inverted index for segment: {}, column: {}", _segmentName, column);
        return;
      }

      // Create a marker file.
      FileUtils.touch(inProgress);
    } else {
      // Marker file exists, which means last run gets interrupted.
      // Remove inverted index if exists.
      // For v1 and v2, it's the actual inverted index. For v3, it's the temporary inverted index.
      FileUtils.deleteQuietly(invertedIndexDir);
    }

    // Create new inverted index for the column.
    LOGGER.info("Creating new lucene based inverted index for segment: {}, column: {}", _segmentName, column);
    int numDocs = columnMetadata.getTotalDocs();
    String objectType = columnMetadata.getObjectType();
    Class<? extends PinotObject> pinotObjectClazz;
    PinotObject pinotObject = null;
    try {
      switch (objectType.toUpperCase()) {
        case "MAP":
          pinotObjectClazz = MapObject.class;
          break;
        case "JSON":
          pinotObjectClazz = JSONObject.class;
          break;
        case "TEXT":
          pinotObjectClazz = TextObject.class;
          break;
        default:
          // custom object type.
          pinotObjectClazz = (Class<? extends PinotObject>) Class.forName(objectType);
      }
      pinotObject = pinotObjectClazz.getConstructor(new Class[]{}).newInstance(new Object[]{});
    } catch (Exception e) {
      LOGGER.error("Error pinot object  for type:{}. Skipping inverted index creation", objectType);
      return;
    }

    try (LuceneIndexCreator luceneIndexCreator = new LuceneIndexCreator(columnMetadata, invertedIndexDir)) {
      try (DataFileReader fwdIndex = getForwardIndexReader(columnMetadata, _segmentWriter)) {
        if (columnMetadata.isSingleValue()) {
          // Single-value column.
          VarByteChunkSingleValueReader svFwdIndex = (VarByteChunkSingleValueReader) fwdIndex;
          for (int i = 0; i < numDocs; i++) {
            byte[] bytes = svFwdIndex.getBytes(i);

            pinotObject.init(bytes);
            luceneIndexCreator.add(pinotObject);
          }
        } else {
          throw new UnsupportedOperationException("Multi Value not supported for complex object types");
        }
        luceneIndexCreator.seal();
      }
    }
    String tarGzPath = TarGzCompressionUtils.createTarGzOfDirectory(invertedIndexDir.getAbsolutePath());

    // For v3, write the generated inverted index file into the single file and remove it.
    if (_segmentVersion == SegmentVersion.v3) {
      LoaderUtils.writeIndexToV3Format(_segmentWriter, column, new File(tarGzPath), ColumnIndexType.INVERTED_INDEX);
    }

    // Delete the marker file.
    FileUtils.deleteQuietly(inProgress);

    LOGGER.info("Created inverted index for segment: {}, column: {}", _segmentName, column);
  }

  private void createInvertedIndexForSimpleField(ColumnMetadata columnMetadata)
      throws IOException {
    String column = columnMetadata.getColumnName();

    File inProgress = new File(_indexDir, column + ".inv.inprogress");
    File invertedIndexFile = new File(_indexDir, column + V1Constants.Indexes.BITMAP_INVERTED_INDEX_FILE_EXTENSION);

    if (!inProgress.exists()) {
      // Marker file does not exist, which means last run ended normally.

      if (_segmentWriter.hasIndexFor(column, ColumnIndexType.INVERTED_INDEX)) {
        // Skip creating inverted index if already exists.

        LOGGER.info("Found inverted index for segment: {}, column: {}", _segmentName, column);
        return;
      }

      // Create a marker file.
      FileUtils.touch(inProgress);
    } else {
      // Marker file exists, which means last run gets interrupted.

      // Remove inverted index if exists.
      // For v1 and v2, it's the actual inverted index. For v3, it's the temporary inverted index.
      FileUtils.deleteQuietly(invertedIndexFile);
    }

    // Create new inverted index for the column.
    LOGGER.info("Creating new inverted index for segment: {}, column: {}", _segmentName, column);
    int numDocs = columnMetadata.getTotalDocs();
    try (OffHeapBitmapInvertedIndexCreator creator = new OffHeapBitmapInvertedIndexCreator(_indexDir,
        columnMetadata.getFieldSpec(), columnMetadata.getCardinality(), numDocs,
        columnMetadata.getTotalNumberOfEntries())) {
      try (DataFileReader fwdIndex = getForwardIndexReader(columnMetadata, _segmentWriter)) {
        if (columnMetadata.isSingleValue()) {
          // Single-value column.

          FixedBitSingleValueReader svFwdIndex = (FixedBitSingleValueReader) fwdIndex;
          for (int i = 0; i < numDocs; i++) {
            creator.add(svFwdIndex.getInt(i));
          }
        } else {
          // Multi-value column.

          SingleColumnMultiValueReader mvFwdIndex = (SingleColumnMultiValueReader) fwdIndex;
          int[] dictIds = new int[columnMetadata.getMaxNumberOfMultiValues()];
          for (int i = 0; i < numDocs; i++) {
            int length = mvFwdIndex.getIntArray(i, dictIds);
            creator.add(dictIds, length);
          }
        }
        creator.seal();
      }
    }

    // For v3, write the generated inverted index file into the single file and remove it.
    if (_segmentVersion == SegmentVersion.v3) {
      LoaderUtils.writeIndexToV3Format(_segmentWriter, column, invertedIndexFile, ColumnIndexType.INVERTED_INDEX);
    }

    // Delete the marker file.
    FileUtils.deleteQuietly(inProgress);

    LOGGER.info("Created inverted index for segment: {}, column: {}", _segmentName, column);
  }

  private DataFileReader getForwardIndexReader(ColumnMetadata columnMetadata, SegmentDirectory.Writer segmentWriter)
      throws IOException {
    PinotDataBuffer buffer = segmentWriter.getIndexFor(columnMetadata.getColumnName(), ColumnIndexType.FORWARD_INDEX);
    int numRows = columnMetadata.getTotalDocs();
    int numBitsPerValue = columnMetadata.getBitsPerElement();
    if (columnMetadata.isSingleValue()) {
      if (columnMetadata.hasDictionary()) {
        return new FixedBitSingleValueReader(buffer, numRows, numBitsPerValue);
      } else {
        return new VarByteChunkSingleValueReader(buffer);
      }
    } else {
      return new FixedBitMultiValueReader(buffer, numRows, columnMetadata.getTotalNumberOfEntries(), numBitsPerValue);
    }
  }
}
