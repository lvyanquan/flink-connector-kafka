/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.connectors.kafka.table;

import org.apache.flink.annotation.Internal;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ConfigOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaPartitioner;
import org.apache.flink.connector.kafka.sink.TransactionNamingStrategy;
import org.apache.flink.connector.kafka.source.KafkaSourceOptions;
import org.apache.flink.streaming.connectors.kafka.config.BoundedMode;
import org.apache.flink.streaming.connectors.kafka.config.StartupMode;
import org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptionsUtil.BoundedOptions;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.catalog.ObjectIdentifier;
import org.apache.flink.table.connector.format.DecodingFormat;
import org.apache.flink.table.connector.format.EncodingFormat;
import org.apache.flink.table.connector.format.Format;
import org.apache.flink.table.connector.sink.DynamicTableSink;
import org.apache.flink.table.connector.source.DynamicTableSource;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.factories.DeserializationFormatFactory;
import org.apache.flink.table.factories.DynamicTableSinkFactory;
import org.apache.flink.table.factories.DynamicTableSourceFactory;
import org.apache.flink.table.factories.FactoryUtil;
import org.apache.flink.table.factories.FactoryUtil.TableFactoryHelper;
import org.apache.flink.table.factories.SerializationFormatFactory;
import org.apache.flink.table.types.DataType;
import org.apache.flink.types.RowKind;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.DELIVERY_GUARANTEE;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.KEY_FIELDS;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.KEY_FIELDS_PREFIX;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.KEY_FORMAT;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.PROPS_BOOTSTRAP_SERVERS;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.PROPS_GROUP_ID;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.SCAN_BOUNDED_MODE;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.SCAN_BOUNDED_SPECIFIC_OFFSETS;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.SCAN_BOUNDED_TIMESTAMP_MILLIS;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.SCAN_PARALLELISM;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.SCAN_STARTUP_MODE;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.SCAN_STARTUP_SPECIFIC_OFFSETS;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.SCAN_STARTUP_TIMESTAMP_MILLIS;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.SCAN_TOPIC_PARTITION_DISCOVERY;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.SINK_PARALLELISM;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.SINK_PARTITIONER;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.TOPIC;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.TOPIC_PATTERN;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.TRANSACTIONAL_ID_PREFIX;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.TRANSACTION_NAMING_STRATEGY;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.VALUE_FIELDS_INCLUDE;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptions.VALUE_FORMAT;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptionsUtil.PROPERTIES_PREFIX;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptionsUtil.StartupOptions;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptionsUtil.autoCompleteSchemaRegistrySubject;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptionsUtil.createKeyFormatProjection;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptionsUtil.createValueFormatProjection;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptionsUtil.getBoundedOptions;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptionsUtil.getFlinkKafkaPartitioner;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptionsUtil.getKafkaProperties;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptionsUtil.getStartupOptions;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptionsUtil.getTopicPattern;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptionsUtil.getTopics;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptionsUtil.validateTableSinkOptions;
import static org.apache.flink.streaming.connectors.kafka.table.KafkaConnectorOptionsUtil.validateTableSourceOptions;

/**
 * Factory for creating configured instances of {@link KafkaDynamicSource} and {@link
 * KafkaDynamicSink}.
 */
@Internal
public class KafkaDynamicTableFactory
        implements DynamicTableSourceFactory, DynamicTableSinkFactory {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaDynamicTableFactory.class);
    private static final ConfigOption<String> SINK_SEMANTIC =
            ConfigOptions.key("sink.semantic")
                    .stringType()
                    .noDefaultValue()
                    .withDescription("Optional semantic when committing.");

    public static final String IDENTIFIER = "kafka";

    @Override
    public String factoryIdentifier() {
        return IDENTIFIER;
    }

    @Override
    public Set<ConfigOption<?>> requiredOptions() {
        final Set<ConfigOption<?>> options = new HashSet<>();
        options.add(PROPS_BOOTSTRAP_SERVERS);
        return options;
    }

    @Override
    public Set<ConfigOption<?>> optionalOptions() {
        final Set<ConfigOption<?>> options = new HashSet<>();
        options.add(FactoryUtil.FORMAT);
        options.add(KEY_FORMAT);
        options.add(KEY_FIELDS);
        options.add(KEY_FIELDS_PREFIX);
        options.add(VALUE_FORMAT);
        options.add(VALUE_FIELDS_INCLUDE);
        options.add(TOPIC);
        options.add(TOPIC_PATTERN);
        options.add(PROPS_GROUP_ID);
        options.add(SCAN_STARTUP_MODE);
        options.add(SCAN_STARTUP_SPECIFIC_OFFSETS);
        options.add(SCAN_TOPIC_PARTITION_DISCOVERY);
        options.add(SCAN_STARTUP_TIMESTAMP_MILLIS);
        options.add(SINK_PARTITIONER);
        options.add(SINK_PARALLELISM);
        options.add(DELIVERY_GUARANTEE);
        options.add(TRANSACTIONAL_ID_PREFIX);
        options.add(SINK_SEMANTIC);
        options.add(SCAN_BOUNDED_MODE);
        options.add(SCAN_BOUNDED_SPECIFIC_OFFSETS);
        options.add(SCAN_BOUNDED_TIMESTAMP_MILLIS);
        options.add(SCAN_PARALLELISM);
        options.add(TRANSACTION_NAMING_STRATEGY);
        return options;
    }

    @Override
    public Set<ConfigOption<?>> forwardOptions() {
        return Stream.of(
                        PROPS_BOOTSTRAP_SERVERS,
                        PROPS_GROUP_ID,
                        TOPIC,
                        TOPIC_PATTERN,
                        SCAN_STARTUP_MODE,
                        SCAN_STARTUP_SPECIFIC_OFFSETS,
                        SCAN_TOPIC_PARTITION_DISCOVERY,
                        SCAN_STARTUP_TIMESTAMP_MILLIS,
                        SCAN_PARALLELISM,
                        SINK_PARTITIONER,
                        SINK_PARALLELISM,
                        TRANSACTIONAL_ID_PREFIX,
                        TRANSACTION_NAMING_STRATEGY)
                .collect(Collectors.toSet());
    }

    @Override
    public DynamicTableSource createDynamicTableSource(Context context) {
        final TableFactoryHelper helper = FactoryUtil.createTableFactoryHelper(this, context);

        final Optional<DecodingFormat<DeserializationSchema<RowData>>> keyDecodingFormat =
                getKeyDecodingFormat(helper);

        final DecodingFormat<DeserializationSchema<RowData>> valueDecodingFormat =
                getValueDecodingFormat(helper);

        helper.validateExcept(PROPERTIES_PREFIX);

        final ReadableConfig tableOptions = helper.getOptions();

        validateTableSourceOptions(tableOptions);

        validatePKConstraints(
                context.getObjectIdentifier(),
                context.getPrimaryKeyIndexes(),
                context.getCatalogTable().getOptions(),
                valueDecodingFormat);

        final StartupOptions startupOptions = getStartupOptions(tableOptions);

        final BoundedOptions boundedOptions = getBoundedOptions(tableOptions);

        final Properties properties = getKafkaProperties(context.getCatalogTable().getOptions());

        // add topic-partition discovery
        final Duration partitionDiscoveryInterval =
                tableOptions.get(SCAN_TOPIC_PARTITION_DISCOVERY);
        properties.setProperty(
                KafkaSourceOptions.PARTITION_DISCOVERY_INTERVAL_MS.key(),
                Long.toString(partitionDiscoveryInterval.toMillis()));

        final DataType physicalDataType = context.getPhysicalRowDataType();

        final int[] keyProjection = createKeyFormatProjection(tableOptions, physicalDataType);

        final int[] valueProjection = createValueFormatProjection(tableOptions, physicalDataType);

        final String keyPrefix = tableOptions.getOptional(KEY_FIELDS_PREFIX).orElse(null);

        final Integer parallelism = tableOptions.getOptional(SCAN_PARALLELISM).orElse(null);

        return createKafkaTableSource(
                physicalDataType,
                keyDecodingFormat.orElse(null),
                valueDecodingFormat,
                keyProjection,
                valueProjection,
                keyPrefix,
                getTopics(tableOptions),
                getTopicPattern(tableOptions),
                properties,
                startupOptions.startupMode,
                startupOptions.specificOffsets,
                startupOptions.startupTimestampMillis,
                boundedOptions.boundedMode,
                boundedOptions.specificOffsets,
                boundedOptions.boundedTimestampMillis,
                context.getObjectIdentifier().asSummaryString(),
                parallelism);
    }

    @Override
    public DynamicTableSink createDynamicTableSink(Context context) {
        final TableFactoryHelper helper =
                FactoryUtil.createTableFactoryHelper(
                        this, autoCompleteSchemaRegistrySubject(context));

        final Optional<EncodingFormat<SerializationSchema<RowData>>> keyEncodingFormat =
                getKeyEncodingFormat(helper);

        final EncodingFormat<SerializationSchema<RowData>> valueEncodingFormat =
                getValueEncodingFormat(helper);

        helper.validateExcept(PROPERTIES_PREFIX);

        final ReadableConfig tableOptions = helper.getOptions();

        final DeliveryGuarantee deliveryGuarantee = validateDeprecatedSemantic(tableOptions);
        validateTableSinkOptions(tableOptions);

        KafkaConnectorOptionsUtil.validateDeliveryGuarantee(tableOptions);

        validatePKConstraints(
                context.getObjectIdentifier(),
                context.getPrimaryKeyIndexes(),
                context.getCatalogTable().getOptions(),
                valueEncodingFormat);

        final DataType physicalDataType = context.getPhysicalRowDataType();

        final int[] keyProjection = createKeyFormatProjection(tableOptions, physicalDataType);

        final int[] valueProjection = createValueFormatProjection(tableOptions, physicalDataType);

        final String keyPrefix = tableOptions.getOptional(KEY_FIELDS_PREFIX).orElse(null);

        final Integer parallelism = tableOptions.getOptional(SINK_PARALLELISM).orElse(null);

        return createKafkaTableSink(
                physicalDataType,
                keyEncodingFormat.orElse(null),
                valueEncodingFormat,
                keyProjection,
                valueProjection,
                keyPrefix,
                getTopics(tableOptions),
                getTopicPattern(tableOptions),
                getKafkaProperties(context.getCatalogTable().getOptions()),
                getFlinkKafkaPartitioner(tableOptions, context.getClassLoader()).orElse(null),
                deliveryGuarantee,
                parallelism,
                tableOptions.get(TRANSACTIONAL_ID_PREFIX),
                tableOptions.get(TRANSACTION_NAMING_STRATEGY));
    }

    // --------------------------------------------------------------------------------------------

    private static Optional<DecodingFormat<DeserializationSchema<RowData>>> getKeyDecodingFormat(
            TableFactoryHelper helper) {
        final Optional<DecodingFormat<DeserializationSchema<RowData>>> keyDecodingFormat =
                helper.discoverOptionalDecodingFormat(
                        DeserializationFormatFactory.class, KEY_FORMAT);
        keyDecodingFormat.ifPresent(
                format -> {
                    if (!format.getChangelogMode().containsOnly(RowKind.INSERT)) {
                        throw new ValidationException(
                                String.format(
                                        "A key format should only deal with INSERT-only records. "
                                                + "But %s has a changelog mode of %s.",
                                        helper.getOptions().get(KEY_FORMAT),
                                        format.getChangelogMode()));
                    }
                });
        return keyDecodingFormat;
    }

    private static Optional<EncodingFormat<SerializationSchema<RowData>>> getKeyEncodingFormat(
            TableFactoryHelper helper) {
        final Optional<EncodingFormat<SerializationSchema<RowData>>> keyEncodingFormat =
                helper.discoverOptionalEncodingFormat(SerializationFormatFactory.class, KEY_FORMAT);
        keyEncodingFormat.ifPresent(
                format -> {
                    if (!format.getChangelogMode().containsOnly(RowKind.INSERT)) {
                        throw new ValidationException(
                                String.format(
                                        "A key format should only deal with INSERT-only records. "
                                                + "But %s has a changelog mode of %s.",
                                        helper.getOptions().get(KEY_FORMAT),
                                        format.getChangelogMode()));
                    }
                });
        return keyEncodingFormat;
    }

    private static DecodingFormat<DeserializationSchema<RowData>> getValueDecodingFormat(
            TableFactoryHelper helper) {
        return helper.discoverOptionalDecodingFormat(
                        DeserializationFormatFactory.class, FactoryUtil.FORMAT)
                .orElseGet(
                        () ->
                                helper.discoverDecodingFormat(
                                        DeserializationFormatFactory.class, VALUE_FORMAT));
    }

    private static EncodingFormat<SerializationSchema<RowData>> getValueEncodingFormat(
            TableFactoryHelper helper) {
        return helper.discoverOptionalEncodingFormat(
                        SerializationFormatFactory.class, FactoryUtil.FORMAT)
                .orElseGet(
                        () ->
                                helper.discoverEncodingFormat(
                                        SerializationFormatFactory.class, VALUE_FORMAT));
    }

    private static void validatePKConstraints(
            ObjectIdentifier tableName,
            int[] primaryKeyIndexes,
            Map<String, String> options,
            Format format) {
        if (primaryKeyIndexes.length > 0
                && format.getChangelogMode().containsOnly(RowKind.INSERT)) {
            Configuration configuration = Configuration.fromMap(options);
            String formatName =
                    configuration
                            .getOptional(FactoryUtil.FORMAT)
                            .orElse(configuration.get(VALUE_FORMAT));
            throw new ValidationException(
                    String.format(
                            "The Kafka table '%s' with '%s' format doesn't support defining PRIMARY KEY constraint"
                                    + " on the table, because it can't guarantee the semantic of primary key.",
                            tableName.asSummaryString(), formatName));
        }
    }

    private static DeliveryGuarantee validateDeprecatedSemantic(ReadableConfig tableOptions) {
        if (tableOptions.getOptional(SINK_SEMANTIC).isPresent()) {
            LOG.warn(
                    "{} is deprecated and will be removed. Please use {} instead.",
                    SINK_SEMANTIC.key(),
                    DELIVERY_GUARANTEE.key());
            return DeliveryGuarantee.valueOf(
                    tableOptions.get(SINK_SEMANTIC).toUpperCase().replace("-", "_"));
        }
        return tableOptions.get(DELIVERY_GUARANTEE);
    }

    // --------------------------------------------------------------------------------------------

    protected KafkaDynamicSource createKafkaTableSource(
            DataType physicalDataType,
            @Nullable DecodingFormat<DeserializationSchema<RowData>> keyDecodingFormat,
            DecodingFormat<DeserializationSchema<RowData>> valueDecodingFormat,
            int[] keyProjection,
            int[] valueProjection,
            @Nullable String keyPrefix,
            @Nullable List<String> topics,
            @Nullable Pattern topicPattern,
            Properties properties,
            StartupMode startupMode,
            Map<TopicPartition, Long> specificStartupOffsets,
            long startupTimestampMillis,
            BoundedMode boundedMode,
            Map<TopicPartition, Long> specificEndOffsets,
            long endTimestampMillis,
            String tableIdentifier,
            Integer parallelism) {
        return new KafkaDynamicSource(
                physicalDataType,
                keyDecodingFormat,
                valueDecodingFormat,
                keyProjection,
                valueProjection,
                keyPrefix,
                topics,
                topicPattern,
                properties,
                startupMode,
                specificStartupOffsets,
                startupTimestampMillis,
                boundedMode,
                specificEndOffsets,
                endTimestampMillis,
                false,
                tableIdentifier,
                parallelism);
    }

    protected KafkaDynamicSink createKafkaTableSink(
            DataType physicalDataType,
            @Nullable EncodingFormat<SerializationSchema<RowData>> keyEncodingFormat,
            EncodingFormat<SerializationSchema<RowData>> valueEncodingFormat,
            int[] keyProjection,
            int[] valueProjection,
            @Nullable String keyPrefix,
            @Nullable List<String> topics,
            @Nullable Pattern topicPattern,
            Properties properties,
            KafkaPartitioner<RowData> partitioner,
            DeliveryGuarantee deliveryGuarantee,
            Integer parallelism,
            @Nullable String transactionalIdPrefix,
            TransactionNamingStrategy transactionNamingStrategy) {
        return new KafkaDynamicSink(
                physicalDataType,
                physicalDataType,
                keyEncodingFormat,
                valueEncodingFormat,
                keyProjection,
                valueProjection,
                keyPrefix,
                topics,
                topicPattern,
                properties,
                partitioner,
                deliveryGuarantee,
                false,
                SinkBufferFlushMode.DISABLED,
                parallelism,
                transactionalIdPrefix,
                transactionNamingStrategy);
    }
}
