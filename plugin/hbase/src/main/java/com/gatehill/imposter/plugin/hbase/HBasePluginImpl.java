package com.gatehill.imposter.plugin.hbase;

import com.gatehill.imposter.ImposterConfig;
import com.gatehill.imposter.plugin.config.BaseConfig;
import com.gatehill.imposter.plugin.config.ConfiguredPlugin;
import com.gatehill.imposter.plugin.hbase.model.MockScanner;
import com.gatehill.imposter.util.FileUtil;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.inject.Inject;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.PrefixFilter;
import org.apache.hadoop.hbase.rest.model.CellModel;
import org.apache.hadoop.hbase.rest.model.CellSetModel;
import org.apache.hadoop.hbase.rest.model.RowModel;
import org.apache.hadoop.hbase.rest.model.ScannerModel;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * @author Pete Cornish {@literal <outofcoffee@gmail.com>}
 */
public class HBasePluginImpl extends ConfiguredPlugin<HBasePluginConfig> {
    private static final Logger LOGGER = LogManager.getLogger(HBasePluginImpl.class);

    @Inject
    private ImposterConfig imposterConfig;

    private Map<String, HBasePluginConfig> tableConfigs;
    private AtomicInteger scannerIdCounter = new AtomicInteger();

    /**
     * Hold scanners for a period of time.
     */
    private Cache<Integer, MockScanner> createdScanners = CacheBuilder.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .build();

    @Override
    protected Class<HBasePluginConfig> getConfigClass() {
        return HBasePluginConfig.class;
    }

    @Override
    protected void configurePlugin(List<HBasePluginConfig> configs) {
        this.tableConfigs = configs.stream()
                .collect(Collectors.toConcurrentMap(HBasePluginConfig::getTableName, hBaseConfig -> hBaseConfig));
    }

    @Override
    public void configureRoutes(Router router) {
        tableConfigs.values().stream()
                .map(config -> ofNullable(config.getBasePath()).orElse(""))
                .distinct()
                .forEach(basePath -> {
                    LOGGER.debug("Adding routes for base path: {}", () -> (basePath.isEmpty() ? "<empty>" : basePath));

                    // the first call obtains a scanner
                    addCreateScannerRoute(router, basePath);

                    // the second call returns the result
                    addReadScannerResultsRoute(router, basePath);
                });
    }

    private void addCreateScannerRoute(Router router, String basePath) {
        router.post(basePath + "/:tableName/scanner").handler(routingContext -> {
            final String tableName = routingContext.request().getParam("tableName");

            // check that the view is registered
            if (!tableConfigs.containsKey(tableName)) {
                LOGGER.error("Received scanner request for unknown table: {}", tableName);

                routingContext.response()
                        .setStatusCode(HttpURLConnection.HTTP_NOT_FOUND)
                        .end();
                return;
            }

            LOGGER.info("Received scanner request for table: {}", tableName);
            final HBasePluginConfig config = tableConfigs.get(tableName);

            // parse request
            final ScannerModel scannerModel;
            try {
                final byte[] rawMessage = routingContext.getBody().getBytes();
                scannerModel = new ScannerModel();
                scannerModel.getObjectFromMessage(rawMessage);

            } catch (IOException e) {
                routingContext.fail(e);
                return;
            }

            LOGGER.debug("{} scanner filter: {}", tableName, scannerModel.getFilter());

            // assert prefix matches if present
            if (ofNullable(config.getPrefix())
                    .map(configPrefix -> isFilterMatch(scannerModel, configPrefix))
                    .orElse(true)) {

                LOGGER.info("Scanner filter prefix matches expected value: {}", config.getPrefix());

            } else {
                LOGGER.error("Scanner filter prefix does not match expected value: {}}", config.getPrefix());
                routingContext.fail(HttpURLConnection.HTTP_INTERNAL_ERROR);
                return;
            }

            // register scanner
            final int scannerId = scannerIdCounter.incrementAndGet();
            createdScanners.put(scannerId, new MockScanner(config, scannerModel));

            final String resultUrl = imposterConfig.getServerUrl() + basePath + "/" + tableName + "/scanner/" + scannerId;

            routingContext.response()
                    .putHeader("Location", resultUrl)
                    .setStatusCode(HttpURLConnection.HTTP_CREATED)
                    .end();
        });
    }

    private boolean isFilterMatch(ScannerModel scannerModel, String configPrefix) {
        final Filter filter;
        try {
            filter = ScannerModel.buildFilter(scannerModel.getFilter());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (filter instanceof PrefixFilter) {
            final String filterPrefix = Bytes.toString(((PrefixFilter) filter).getPrefix());
            return configPrefix.equals(filterPrefix);
        }
        return false;
    }

    private void addReadScannerResultsRoute(Router router, String basePath) {
        router.get(basePath + "/:tableName/scanner/:scannerId").handler(routingContext -> {
            final String tableName = routingContext.request().getParam("tableName");
            final String scannerId = routingContext.request().getParam("scannerId");

            // query param e.g. ?n=1
            final int rows = Integer.valueOf(routingContext.request().getParam("n"));

            // check that the table is registered
            if (!tableConfigs.containsKey(tableName)) {
                LOGGER.error("Received result request for unknown table: {}", tableName);

                routingContext.response()
                        .setStatusCode(HttpURLConnection.HTTP_NOT_FOUND)
                        .end();
                return;
            }

            // check that the scanner was created
            final Optional<MockScanner> optionalScanner = ofNullable(createdScanners.getIfPresent(Integer.valueOf(scannerId)));
            if (!optionalScanner.isPresent()) {
                LOGGER.error("Received result request for non-existent scanner {} for table: {}", scannerId, tableName);

                routingContext.response()
                        .setStatusCode(HttpURLConnection.HTTP_NOT_FOUND)
                        .end();
                return;
            }

            LOGGER.info("Received result request for {} rows from scanner {} for table: {}", rows, scannerId, tableName);
            final MockScanner scanner = optionalScanner.get();

            // load result
            final BaseConfig config = tableConfigs.get(tableName);
            final JsonArray results = FileUtil.loadResponseAsJsonArray(imposterConfig, config);

            // build results
            final CellSetModel cellSetModel = new CellSetModel();

            // start the row counter from the last position in the scanner
            for (int i = scanner.getRowCounter().get(); i < rows && i < results.size(); i++) {
                final JsonObject result = results.getJsonObject(i);

                final RowModel row = new RowModel();

                // TODO consider setting key to prefix from scanner filter
                row.setKey(Bytes.toBytes("rowKey" + scanner.getRowCounter().incrementAndGet()));

                // add cells from result file
                for (String fieldName : result.fieldNames()) {
                    final CellModel cell = new CellModel();
                    cell.setColumn(Bytes.toBytes(fieldName));
                    cell.setValue(Bytes.toBytes(result.getString(fieldName)));
                    row.addCell(cell);
                }

                cellSetModel.addRow(row);
            }

            final boolean exhausted = (scanner.getRowCounter().get() >= results.size());
            if (exhausted) {
                LOGGER.info("Scanner {} for table: {} exhausted", scannerId, tableName);
                createdScanners.invalidate(scannerId);
            }

            LOGGER.info("Returning {} rows from scanner {} for table: {}", cellSetModel.getRows().size(), scannerId, tableName);

            final byte[] protobufOutput = cellSetModel.createProtobufOutput();

            routingContext.response()
                    .setStatusCode(HttpURLConnection.HTTP_OK)
                    .end(Buffer.buffer(protobufOutput));
        });
    }
}
