/*
 * Copyright (C) 2021 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
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
package nl.knaw.dans.migration.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import nl.knaw.dans.lib.dataverse.DataverseItemDeserializer;
import nl.knaw.dans.lib.dataverse.MetadataFieldDeserializer;
import nl.knaw.dans.lib.dataverse.ResultItemDeserializer;
import nl.knaw.dans.lib.dataverse.model.dataset.MetadataField;
import nl.knaw.dans.lib.dataverse.model.dataverse.DataverseItem;
import nl.knaw.dans.lib.dataverse.model.search.ResultItem;
import nl.knaw.dans.migration.core.tables.ExpectedDataset;
import nl.knaw.dans.migration.core.tables.ExpectedFile;
import nl.knaw.dans.migration.db.ExpectedDatasetDAO;
import nl.knaw.dans.migration.db.ExpectedFileDAO;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static nl.knaw.dans.migration.core.HttpHelper.executeReq;

public class VaultLoader extends ExpectedLoader {

  private static final Logger log = LoggerFactory.getLogger(VaultLoader.class);

  private final URI bagStoreBaseUri;
  private final URI bagIndexBaseUri;
  private final URI bagSeqUri;
  private final ObjectMapper mapper;
  private final Map<String, String> accountSubStitues;

  public VaultLoader(ExpectedFileDAO expectedFileDAO, ExpectedDatasetDAO expectedDatasetDAO, URI bagStoreBaseUri, URI bagIndexBaseUri, File configDir) {
    super(expectedFileDAO, expectedDatasetDAO, configDir);
    bagSeqUri = bagIndexBaseUri.resolve("bag-sequence");
    this.bagStoreBaseUri = bagStoreBaseUri;
    this.bagIndexBaseUri = bagIndexBaseUri;
    this.accountSubStitues = Accounts.load(configDir);


    mapper = new ObjectMapper();
    SimpleModule module = new SimpleModule();
    module.addDeserializer(MetadataField.class, new MetadataFieldDeserializer());
    module.addDeserializer(DataverseItem.class, new DataverseItemDeserializer());
    module.addDeserializer(ResultItem.class, new ResultItemDeserializer(mapper));
    mapper.registerModule(module);
  }

  public void loadFromVault(UUID uuid) {
    final BagInfo bagInfo = readBagInfo(uuid.toString());
    log.trace("from input {}", bagInfo);
    if (bagInfo.getBagId() == null)
      log.trace("skipping: not found/parsed");
    else if (!bagInfo.getBagId().equals(bagInfo.getBaseId()))
      log.info("Skipping {}, it is another version of {}", uuid, bagInfo.getBaseId());
    else {
      log.trace("Processing {}", bagInfo);
      String[] bagSeq = readBagSequence(uuid);
      if (bagSeq.length <= 1)
        processBag(uuid.toString(), bagInfo);
      else {
        List<BagInfo> bagInfos= StreamSupport
            .stream(Arrays.stream(bagSeq).spliterator(), false)
            .map(this::readBagInfo)
            .sorted(new BagInfoComparator()).collect(Collectors.toList());
        int count = 0;
        for (BagInfo info : bagInfos) {
          log.trace("{} from sequence {}", ++count, info);
          processBag(info.getBaseId(), info);
        }
      }
    }
  }

  /** note: easy-convert-bag-to-deposit does not add emd.xml to bags from the vault */
  private static final String[] migrationFiles = { "provenance.xml", "dataset.xml", "files.xml" };

  private void processBag(String uuid, BagInfo bagInfo) {
    String doi = bagInfo.getDoi();
    byte[] ddmBytes = readDDM(uuid).getBytes(StandardCharsets.UTF_8);// parsed twice to reuse code shared with EasyFileLoader
    ExpectedDataset expectedDataset;
    if (ddmBytes.length == 0) {
      expectedDataset = new ExpectedDataset();
      // presuming deactivated, logging shows whether it was indeed deactivated or not found
      expectedDataset.setDeleted(true);
    } else {
      String depositor = readDepositor(uuid);
      DatasetRights datasetRights = DatasetRightsHandler.parseRights(new ByteArrayInputStream(ddmBytes));
      expectedDataset = datasetRights.expectedDataset(accountSubStitues.getOrDefault(depositor, depositor));
      expectedDataset.setLicense(DatasetLicenseHandler.parseLicense(new ByteArrayInputStream(ddmBytes), datasetRights.accessCategory));
      // now that we collected everything from the bag, we start processing the files
      Map<String, FileRights> filesXml = readFileMeta(uuid);
      readManifest(uuid).forEach(m ->
          createExpected(doi, m, filesXml, datasetRights.defaultFileRights)
      );
      expectedMigrationFiles(doi, migrationFiles, datasetRights.defaultFileRights);
    }
    expectedDataset.setDoi(doi);
    saveExpectedDataset(expectedDataset);
  }

  private void createExpected(String doi, ManifestCsv m, Map<String, FileRights> fileRightsMap, FileRights defaultFileRights) {
    String path = m.getPath();
    FileRights fileRights = fileRightsMap.get(path).applyDefaults(defaultFileRights);
    log.trace("{} {}", path, fileRights);
    retriedSave(new ExpectedFile(doi, m, fileRights));
  }

  private Stream<ManifestCsv> readManifest(String uuid) {
    URI uri = bagStoreBaseUri
        .resolve("bags/")
        .resolve(uuid+"/")
        .resolve("manifest-sha1.txt");
    try {
      return ManifestCsv.parse(executeReq(new HttpGet(uri), true));
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private Map<String, FileRights> readFileMeta(String uuid) {
    URI uri = bagStoreBaseUri
        .resolve("bags/")
        .resolve(uuid+"/")
        .resolve("metadata/")
        .resolve("files.xml");
    try {
      String xmlString = executeReq(new HttpGet(uri), true);
      return FileRightsHandler.parseRights(new ByteArrayInputStream(xmlString.getBytes(StandardCharsets.UTF_8)));
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private String readDDM(String uuid) {
    URI uri = bagStoreBaseUri
        .resolve("bags/")
        .resolve(uuid+"/")
        .resolve("metadata/")
        .resolve("dataset.xml");
    try {
      return executeReq(new HttpGet(uri), true);
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private String readDepositor(String uuid) {
    URI uri = bagStoreBaseUri
        .resolve("bags/")
        .resolve(uuid+"/")
        .resolve("bag-info.txt");
    try {
      Optional<String> account = Arrays.stream(executeReq(new HttpGet(uri), true)
              .split(System.lineSeparator()))
              .filter(l -> l.startsWith("EASY-User-Account"))
              .map(l -> l.replaceAll(".*:","").trim())
              .findFirst();
      if (!account.isPresent())
        throw new IllegalStateException("No EASY-User-Account in bag-info.txt of "+ uuid);
      return account.get();
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private BagInfo readBagInfo(String uuid) {
    URI uri = bagIndexBaseUri
        .resolve("bags/")
        .resolve(uuid);
    try {
      String s = executeReq(new HttpGet(uri), true);
      if ("".equals(s)) return new BagInfo(); // not found
      else try {
        return mapper.readValue(s, BagInfoEnvelope.class).getResult().getBagInfo();
      }
      catch (JsonProcessingException e) {
        log.error("Could not parse BagInfo of {} reason {} content {}", uuid, e.getMessage(), s);
        return new BagInfo();
      }
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }


  private String[] readBagSequence(UUID uuid) {
    URIBuilder builder = new URIBuilder(bagSeqUri)
        .setParameter("contains", uuid.toString());
    try {
      return executeReq(new HttpGet(builder.build()), false).split(System.lineSeparator());
    }
    catch (IOException | URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }
}