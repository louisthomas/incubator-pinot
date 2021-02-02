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
package org.apache.pinot.plugin.inputformat.parquet;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.parquet.column.page.PageReadStore;
import org.apache.parquet.example.data.Group;
import org.apache.parquet.example.data.simple.convert.GroupRecordConverter;
import org.apache.parquet.format.converter.ParquetMetadataConverter;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.metadata.ParquetMetadata;
import org.apache.parquet.io.ColumnIOFactory;
import org.apache.parquet.io.MessageColumnIO;
import org.apache.parquet.schema.MessageType;
import org.apache.pinot.spi.data.readers.GenericRow;
import org.apache.pinot.spi.data.readers.RecordReader;
import org.apache.pinot.spi.data.readers.RecordReaderConfig;


/**
 * Record reader for Parquet file.
 */
public class ParquetNativeRecordReader implements RecordReader {
  private Path _dataFilePath;
  private ParquetNativeRecordExtractor _recordExtractor;
  private MessageType _schema;
  private ParquetMetadata _parquetMetadata;
  private ParquetFileReader _parquetFileReader;
  private Group _nextRecord;
  private PageReadStore _pageReadStore;
  private MessageColumnIO _columnIO;
  private org.apache.parquet.io.RecordReader _parquetRecordReader;
  private int _currentPageIdx;

  @Override
  public void init(File dataFile, @Nullable Set<String> fieldsToRead, @Nullable RecordReaderConfig recordReaderConfig)
      throws IOException {
    _dataFilePath = new Path(dataFile.getAbsolutePath());
    Configuration conf = new Configuration();
    _parquetMetadata = ParquetFileReader.readFooter(conf, _dataFilePath, ParquetMetadataConverter.NO_FILTER);
    _recordExtractor = new ParquetNativeRecordExtractor();
    _recordExtractor.init(fieldsToRead, null);
    _schema = _parquetMetadata.getFileMetaData().getSchema();
    _parquetFileReader =
        new ParquetFileReader(conf, _parquetMetadata.getFileMetaData(), _dataFilePath, _parquetMetadata.getBlocks(),
            _schema.getColumns());
    _pageReadStore = _parquetFileReader.readNextRowGroup();
    _columnIO = new ColumnIOFactory().getColumnIO(_schema);
    _parquetRecordReader = _columnIO.getRecordReader(_pageReadStore, new GroupRecordConverter(_schema));
    _currentPageIdx = 0;
  }

  @Override
  public boolean hasNext() {
    if (_pageReadStore == null) {
      return false;
    }
    if (_pageReadStore.getRowCount() - _currentPageIdx >= 1) {
      // System.out.println("_pageReadStore.getRowCount() = " + _pageReadStore.getRowCount() + ", _currentPageIdx = " + _currentPageIdx);
      return true;
    }
    try {
      _pageReadStore = _parquetFileReader.readNextRowGroup();
      _currentPageIdx = 0;
      if (_pageReadStore == null) {
        return false;
      }
      _parquetRecordReader = _columnIO.getRecordReader(_pageReadStore, new GroupRecordConverter(_schema));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return hasNext();
  }

  @Override
  public GenericRow next()
      throws IOException {
    return next(new GenericRow());
  }

  @Override
  public GenericRow next(GenericRow reuse)
      throws IOException {
    _nextRecord = (Group) _parquetRecordReader.read();
    _recordExtractor.extract(_nextRecord, reuse);
    _currentPageIdx++;
    return reuse;
  }

  @Override
  public void rewind()
      throws IOException {
    _parquetFileReader.close();
    Configuration conf = new Configuration();
    _parquetFileReader =
        new ParquetFileReader(conf, _parquetMetadata.getFileMetaData(), _dataFilePath, _parquetMetadata.getBlocks(),
            _schema.getColumns());
    _pageReadStore = _parquetFileReader.readNextRowGroup();
    _parquetRecordReader = _columnIO.getRecordReader(_pageReadStore, new GroupRecordConverter(_schema));
    _currentPageIdx = 0;
  }

  @Override
  public void close()
      throws IOException {
    _parquetFileReader.close();
  }
}
