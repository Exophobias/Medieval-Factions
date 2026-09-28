package com.dansplugins.factionsystem.config;

import com.dansplugins.factionsystem.MedievalFactions;
import com.dansplugins.factionsystem.legacy.MfLegacyDataMigrator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigLifecycleTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void freshConfigIsInstalledOwnerOnlyAndCurrentLoadIsByteIdempotent() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        String template = bundledTemplate();

        ConfigLifecycle.Result created = ConfigLifecycle.prepare(config, template);

        assertEquals(ConfigLifecycle.State.CREATED, created.state());
        assertTrue(created.compatible());
        assertArrayEquals(template.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(config));
        assertArrayEquals(Files.readAllBytes(config), created.snapshot().fileBytes());
        assertEquals(5, created.snapshot().configuration().getInt("config-version"));
        assertFalse(created.snapshot().configuration().getBoolean(
                "factions.allowLeaderlessFactions"));
        assertTrue(created.snapshot().configuration().getBoolean(
                "factions.adminOnlyLeaderlessFactions"));
        assertEquals(30, created.snapshot().configuration().getInt(
                "factions.factionHomeCooldownMinutes"));
        assertEquals(4, created.snapshot().configuration().getInt(
                "factions.defaults.flags.maxEmbassyChunks"));
        assertOwnerOnly(config);

        byte[] currentBytes = Files.readAllBytes(config);
        ConfigLifecycle.Result current = ConfigLifecycle.prepare(config, template);

        assertEquals(ConfigLifecycle.State.CURRENT, current.state());
        assertNull(current.backup());
        assertArrayEquals(currentBytes, Files.readAllBytes(config));
        assertArrayEquals(currentBytes, current.snapshot().fileBytes());
    }

    @Test
    void historicalUnversionedFixtureMigratesTemplateFirstWithoutLosingSecretsOrExtensions()
            throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        byte[] historical = resourceBytes("/config/historical-unversioned.yml");
        Files.write(config, historical);

        ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, bundledTemplate());

        assertEquals(ConfigLifecycle.State.UPGRADED, result.state(), result.detail());
        assertEquals(0, result.sourceVersion());
        assertNull(result.backup());
        assertOwnerOnly(config);
        assertEquals(5, result.snapshot().configuration().getInt("config-version"));
        assertEquals(7, result.snapshot().configuration().getInt("players.initialPower"));
        assertEquals(42, result.snapshot().configuration().getInt("factions.maxMembers"));
        assertEquals("historical-database-secret",
                result.snapshot().configuration().getString("database.password"));
        assertEquals("historical-dpc-secret",
                result.snapshot().configuration().getString("dpc-api.key"));
        assertEquals("enabled", result.snapshot().configuration()
                .getString("factions.defaults.flags.heraldry-extension"));
        assertTrue(result.snapshot().configuration()
                .getBoolean("factions.integration-extension.nested.retained"));
        assertTrue(result.snapshot().configuration()
                .getBoolean("third-party-hooks.audit.enabled"));
        assertTrue(result.snapshot().configuration()
                .getBoolean("factions.adminOnlyLeaderlessFactions"));
        assertEquals(30, result.snapshot().configuration().getInt(
                "factions.factionHomeCooldownMinutes"));
        assertEquals(4, result.snapshot().configuration().getInt(
                "factions.defaults.flags.maxEmbassyChunks"));
        assertFalse(result.detail().contains("historical-database-secret"));
        assertFalse(result.detail().contains("historical-dpc-secret"));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(1L, files.count(), "migration must not create a backup");
        }
        String migratedText = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(migratedText.contains("Makes each chunk cost slightly more power"));
        assertTrue(migratedText.contains("# Direct block entries"));
        assertTrue(migratedText.contains("&simple_blocks"));
        assertTrue(migratedText.contains("*simple_blocks"));

        YamlConfiguration template = load(bundledTemplate());
        assertKnownKeysPrecedeExtensions(template, result.snapshot().configuration());
        List<String> factionKeys = new ArrayList<>(result.snapshot().configuration()
                .getConfigurationSection("factions").getKeys(false));
        assertTrue(factionKeys.indexOf("demesneCurve") < factionKeys.indexOf("contiguousClaims"));
        assertTrue(factionKeys.indexOf("allowLeaderlessFactions")
                < factionKeys.indexOf("integration-extension"));
        assertEquals(factionKeys.indexOf("factionHomeTeleportDelay") + 1,
                factionKeys.indexOf("factionHomeCooldownMinutes"));

        byte[] migratedBytes = Files.readAllBytes(config);
        ConfigLifecycle.Result second = ConfigLifecycle.prepare(config, bundledTemplate());
        assertEquals(ConfigLifecycle.State.CURRENT, second.state());
        assertNull(second.backup());
        assertArrayEquals(migratedBytes, Files.readAllBytes(config));
    }

    @Test
    void schemaOneMigrationAddsUpstreamKeysWithoutLosingOperatorChoices() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        Files.writeString(config, """
                config-version: 1
                database:
                  password: operator-database-secret
                storage:
                  type: database
                  json:
                    path: ./operator-json
                dpc-api:
                  url: https://dansplugins.com/api/v1/factions
                  key: operator-dpc-secret
                usage-reporting:
                  enabled: true
                  endpoint: https://operator.example.test
                  key: operator-trace-key
                factions:
                  demesneCurve:
                    enabled: true
                    freeChunks: 48
                  allowLeaderlessFactions: true
                extension:
                  retained: preserved
                """, StandardCharsets.UTF_8);

        ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, bundledTemplate());

        assertEquals(ConfigLifecycle.State.UPGRADED, result.state(), result.detail());
        assertEquals(1, result.sourceVersion());
        assertEquals(5, result.installedVersion());
        assertNull(result.backup());
        var values = result.snapshot().configuration();
        assertEquals("operator-database-secret", values.getString("database.password"));
        assertEquals("operator-dpc-secret", values.getString("dpc-api.key"));
        assertEquals("https://api.dansplugins.com/api/v1/factions",
                values.getString("dpc-api.url"));
        assertEquals("database", values.getString("storage.type"));
        assertEquals("./operator-json", values.getString("storage.json.path"));
        assertTrue(values.getBoolean("usage-reporting.enabled"));
        assertEquals("https://operator.example.test", values.getString("usage-reporting.endpoint"));
        assertEquals("operator-trace-key", values.getString("usage-reporting.key"));
        assertTrue(values.getBoolean("factions.demesneCurve.enabled"));
        assertEquals(48, values.getInt("factions.demesneCurve.freeChunks"));
        assertTrue(values.getBoolean("factions.allowLeaderlessFactions"));
        assertFalse(values.getBoolean("factions.adminOnlyLeaderlessFactions"));
        assertEquals(30, values.getInt("factions.factionHomeCooldownMinutes"));
        assertEquals(4, values.getInt("factions.defaults.flags.maxEmbassyChunks"));
        assertEquals("preserved", values.getString("extension.retained"));
        assertFalse(values.contains("factions.nonMembersCanInteractWithEntities"));
        assertOwnerOnly(config);
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(1L, files.count(), "schema migration must not create a backup");
        }
    }

    @Test
    void schemaOneMigrationPreservesCustomDpcUrlAndDefaultsTelemetryOff() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        Files.writeString(config, """
                config-version: 1
                dpc-api:
                  url: https://operator.example.test/factions
                """, StandardCharsets.UTF_8);

        ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, bundledTemplate());

        assertTrue(result.compatible(), result.detail());
        assertEquals("https://operator.example.test/factions",
                result.snapshot().configuration().getString("dpc-api.url"));
        assertFalse(result.snapshot().configuration().getBoolean("usage-reporting.enabled"));
        assertEquals("database", result.snapshot().configuration().getString("storage.type"));
        assertTrue(result.snapshot().configuration()
                .getBoolean("factions.adminOnlyLeaderlessFactions"));
    }

    @Test
    void schemaTwoMigrationPreservesLeaderlessCreationAccessAndOperatorValues()
            throws Exception {
        for (boolean previouslyAllowed : List.of(false, true)) {
            Path directory = temporaryDirectory.resolve("schema-two-" + previouslyAllowed);
            Files.createDirectories(directory);
            Path config = directory.resolve("config.yml");
            Files.writeString(config, """
                    config-version: 2
                    database:
                      password: operator-database-secret
                    factions:
                      allowLeaderlessFactions: %s
                      integration-extension:
                        retained: true
                    extension:
                      retained: preserved
                    """.formatted(previouslyAllowed), StandardCharsets.UTF_8);

            ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, bundledTemplate());

            assertEquals(ConfigLifecycle.State.UPGRADED, result.state(), result.detail());
            assertEquals(2, result.sourceVersion());
            assertEquals(5, result.installedVersion());
            assertEquals(previouslyAllowed,
                    result.snapshot().configuration().getBoolean(
                            "factions.allowLeaderlessFactions"));
            assertEquals(!previouslyAllowed,
                    result.snapshot().configuration().getBoolean(
                            "factions.adminOnlyLeaderlessFactions"));
            assertEquals(30, result.snapshot().configuration().getInt(
                    "factions.factionHomeCooldownMinutes"));
            assertEquals(4, result.snapshot().configuration().getInt(
                    "factions.defaults.flags.maxEmbassyChunks"));
            assertEquals("operator-database-secret",
                    result.snapshot().configuration().getString("database.password"));
            assertTrue(result.snapshot().configuration().getBoolean(
                    "factions.integration-extension.retained"));
            assertEquals("preserved", result.snapshot().configuration()
                    .getString("extension.retained"));
            assertOwnerOnly(config);
            byte[] migratedBytes = Files.readAllBytes(config);
            assertEquals(ConfigLifecycle.State.CURRENT,
                    ConfigLifecycle.prepare(config, bundledTemplate()).state());
            assertArrayEquals(migratedBytes, Files.readAllBytes(config));
            try (var files = Files.list(directory)) {
                assertEquals(1L, files.count(), "schema migration must not create a backup");
            }
        }
    }

    @Test
    void schemaThreeMigrationAddsCooldownWithoutLosingChoicesOrExtensions()
            throws Exception {
        for (String configuredCooldown : List.of("", "0", "17")) {
            Path directory = temporaryDirectory.resolve("schema-three-" +
                    (configuredCooldown.isEmpty() ? "default" : configuredCooldown));
            Files.createDirectories(directory);
            Path config = directory.resolve("config.yml");
            String cooldownLine = configuredCooldown.isEmpty() ? ""
                    : "  factionHomeCooldownMinutes: " + configuredCooldown + "\n";
            Files.writeString(config, """
                    config-version: 3
                    database:
                      password: operator-database-secret
                    factions:
                      factionHomeTeleportDelay: 9
                    %s  integration-extension:
                        retained: true
                    third-party-extension:
                      retained: preserved
                    """.formatted(cooldownLine), StandardCharsets.UTF_8);

            ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, bundledTemplate());

            assertEquals(ConfigLifecycle.State.UPGRADED, result.state(), result.detail());
            assertEquals(3, result.sourceVersion());
            assertEquals(5, result.installedVersion());
            assertEquals(configuredCooldown.isEmpty() ? 30 : Integer.parseInt(configuredCooldown),
                    result.snapshot().configuration().getInt(
                            "factions.factionHomeCooldownMinutes"));
            assertEquals(9, result.snapshot().configuration().getInt(
                    "factions.factionHomeTeleportDelay"));
            assertEquals(4, result.snapshot().configuration().getInt(
                    "factions.defaults.flags.maxEmbassyChunks"));
            assertEquals("operator-database-secret", result.snapshot().configuration()
                    .getString("database.password"));
            assertTrue(result.snapshot().configuration()
                    .getBoolean("factions.integration-extension.retained"));
            assertEquals("preserved", result.snapshot().configuration()
                    .getString("third-party-extension.retained"));
            List<String> factionKeys = new ArrayList<>(result.snapshot().configuration()
                    .getConfigurationSection("factions").getKeys(false));
            assertEquals(factionKeys.indexOf("factionHomeTeleportDelay") + 1,
                    factionKeys.indexOf("factionHomeCooldownMinutes"));
            assertTrue(factionKeys.indexOf("factionHomeCooldownMinutes")
                    < factionKeys.indexOf("integration-extension"));
            assertNull(result.backup());
            assertOwnerOnly(config);
            byte[] migratedBytes = Files.readAllBytes(config);
            assertEquals(ConfigLifecycle.State.CURRENT,
                    ConfigLifecycle.prepare(config, bundledTemplate()).state());
            assertArrayEquals(migratedBytes, Files.readAllBytes(config));
            try (var files = Files.list(directory)) {
                assertEquals(1L, files.count(), "schema migration must not create a backup");
            }
        }
    }

    @Test
    void schemaFourMigrationAddsEmbassyAllowanceAndPreservesExplicitExtensions()
            throws Exception {
        for (String configuredMaximum : List.of("", "0", "9", "4096")) {
            Path directory = temporaryDirectory.resolve("schema-four-"
                    + (configuredMaximum.isEmpty() ? "default" : configuredMaximum));
            Files.createDirectories(directory);
            Path config = directory.resolve("config.yml");
            String maximumLine = configuredMaximum.isEmpty() ? ""
                    : "      maxEmbassyChunks: " + configuredMaximum + "\n";
            Files.writeString(config, """
                    config-version: 4
                    database:
                      password: operator-database-secret
                    factions:
                      factionHomeCooldownMinutes: 17
                      defaults:
                        flags:
                          protectVillagerTrade: false
                    %s      extension-flag: preserved
                    third-party-extension:
                      retained: true
                    """.formatted(maximumLine), StandardCharsets.UTF_8);

            ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, bundledTemplate());

            assertEquals(ConfigLifecycle.State.UPGRADED, result.state(), result.detail());
            assertEquals(4, result.sourceVersion());
            assertEquals(5, result.installedVersion());
            var values = result.snapshot().configuration();
            assertEquals(configuredMaximum.isEmpty() ? 4 : Integer.parseInt(configuredMaximum),
                    values.getInt("factions.defaults.flags.maxEmbassyChunks"));
            assertEquals(17, values.getInt("factions.factionHomeCooldownMinutes"));
            assertFalse(values.getBoolean("factions.defaults.flags.protectVillagerTrade"));
            assertEquals("operator-database-secret", values.getString("database.password"));
            assertEquals("preserved", values.getString("factions.defaults.flags.extension-flag"));
            assertTrue(values.getBoolean("third-party-extension.retained"));
            assertKnownKeysPrecedeExtensions(load(bundledTemplate()), values);
            List<String> flagKeys = new ArrayList<>(values.getConfigurationSection(
                    "factions.defaults.flags").getKeys(false));
            assertEquals(flagKeys.indexOf("protectVillagerTrade") + 1,
                    flagKeys.indexOf("maxEmbassyChunks"));
            assertEquals(flagKeys.indexOf("maxEmbassyChunks") + 1,
                    flagKeys.indexOf("coatofarms"));
            assertNull(result.backup());
            assertOwnerOnly(config);
            byte[] migrated = Files.readAllBytes(config);
            assertEquals(ConfigLifecycle.State.CURRENT,
                    ConfigLifecycle.prepare(config, bundledTemplate()).state());
            assertArrayEquals(migrated, Files.readAllBytes(config));
            try (var files = Files.list(directory)) {
                assertEquals(1L, files.count(), "schema migration must not create a backup");
            }
        }
    }

    @Test
    void invalidEmbassyAllowanceIsRejectedAcrossEverySchemaWithoutWrites()
            throws Exception {
        for (int schema : List.of(0, 1, 2, 3, 4, 5)) {
            List<String> invalid = List.of("-1", "4097", "2147483648", "'4'", "4.0", "true");
            for (int index = 0; index < invalid.size(); index++) {
                Path directory = temporaryDirectory.resolve("invalid-embassy-" + schema + "-" + index);
                Files.createDirectories(directory);
                Path config = directory.resolve("config.yml");
                byte[] original = ("config-version: " + schema + "\n"
                        + "factions:\n  defaults:\n    flags:\n      maxEmbassyChunks: "
                        + invalid.get(index) + "\n").getBytes(StandardCharsets.UTF_8);
                Files.write(config, original);

                ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, bundledTemplate());

                assertEquals(ConfigLifecycle.State.INVALID, result.state(), result.detail());
                assertEquals("invalid configuration at factions.defaults.flags.maxEmbassyChunks",
                        result.detail());
                assertArrayEquals(original, Files.readAllBytes(config));
                assertNull(result.backup());
                try (var files = Files.list(directory)) {
                    assertEquals(1L, files.count(), "invalid config must not create artifacts");
                }
            }
        }
    }

    @Test
    void sparseCurrentConfigReadsEmbassyDefaultWithoutWritingMissingKeys() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        byte[] original = "config-version: 5\n".getBytes(StandardCharsets.UTF_8);
        Files.write(config, original);

        ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, bundledTemplate());

        assertEquals(ConfigLifecycle.State.CURRENT, result.state(), result.detail());
        assertEquals(4, result.snapshot().configuration().getInt(
                "factions.defaults.flags.maxEmbassyChunks"));
        assertArrayEquals(original, Files.readAllBytes(config));
    }

    @Test
    void invalidFactionHomeCooldownIsRejectedWithoutChangingInstalledBytes()
            throws Exception {
        for (int schema : List.of(3, 4, 5)) {
            for (String value : List.of("-1", "'30'", "2147483648")) {
                Path directory = temporaryDirectory.resolve("invalid-home-cooldown-"
                        + schema + "-" + value.replaceAll("[^0-9]", "x"));
                Files.createDirectories(directory);
                Path config = directory.resolve("config.yml");
                byte[] original = ("config-version: " + schema + "\n"
                        + "factions:\n  factionHomeCooldownMinutes: " + value + "\n")
                        .getBytes(StandardCharsets.UTF_8);
                Files.write(config, original);

                ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, bundledTemplate());

                assertEquals(ConfigLifecycle.State.INVALID, result.state(), result.detail());
                assertEquals("invalid configuration at factions.factionHomeCooldownMinutes",
                        result.detail());
                assertArrayEquals(original, Files.readAllBytes(config));
                assertNull(result.backup());
                try (var files = Files.list(directory)) {
                    assertEquals(1L, files.count(), "invalid config must not create artifacts");
                }
            }
        }
    }

    @Test
    void schemaZeroLeaderlessOptInMigratesThroughEverySchema() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        Files.writeString(config, """
                factions:
                  allowLeaderlessFactions: true
                extension:
                  retained: preserved
                """, StandardCharsets.UTF_8);

        ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, bundledTemplate());

        assertEquals(ConfigLifecycle.State.UPGRADED, result.state(), result.detail());
        assertEquals(0, result.sourceVersion());
        assertEquals(5, result.installedVersion());
        assertTrue(result.snapshot().configuration().getBoolean(
                "factions.allowLeaderlessFactions"));
        assertFalse(result.snapshot().configuration().getBoolean(
                "factions.adminOnlyLeaderlessFactions"));
        assertEquals(30, result.snapshot().configuration().getInt(
                "factions.factionHomeCooldownMinutes"));
        assertEquals("preserved", result.snapshot().configuration()
                .getString("extension.retained"));
    }

    @Test
    void schemaOneMigrationRejectsInvalidStorageAndReportingWithoutWrites() throws Exception {
        List<String> invalid = List.of(
                "storage:\n  type: unsupported\n",
                "storage:\n  json:\n    path: '   '\n",
                "usage-reporting:\n  enabled: 'false'\n"
        );
        for (int index = 0; index < invalid.size(); index++) {
            Path directory = temporaryDirectory.resolve("invalid-schema-2-" + index);
            Files.createDirectories(directory);
            Path config = directory.resolve("config.yml");
            byte[] original = ("config-version: 1\n" + invalid.get(index))
                    .getBytes(StandardCharsets.UTF_8);
            Files.write(config, original);

            ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, bundledTemplate());

            assertEquals(ConfigLifecycle.State.INVALID, result.state(), result.detail());
            assertArrayEquals(original, Files.readAllBytes(config));
            assertNull(result.backup());
            try (var files = Files.list(directory)) {
                assertEquals(1L, files.count(), "invalid migration must not write artifacts");
            }
        }
    }

    @Test
    void bundledAnchorAndAliasValuesSurviveSchemaZeroCanonicalization() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        Files.writeString(config, "language: en-US\n", StandardCharsets.UTF_8);
        String templateText = bundledTemplate();
        YamlConfiguration template = load(templateText);

        ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, templateText);

        assertTrue(result.compatible());
        assertEquals(template.getList("gates.restrictedBlocks"),
                result.snapshot().configuration().getList("gates.restrictedBlocks"));
        assertFalse(result.snapshot().configuration().getList("gates.restrictedBlocks").isEmpty());
        assertArrayEquals(Files.readAllBytes(config), result.snapshot().fileBytes());
    }

    @Test
    void fullHistoricalBundledConfigWithoutMarkerKeepsCanonicalAnchorsAndComments()
            throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        String template = bundledTemplate();
        String unversioned = template.replaceFirst(
                "(?s)\\A# Independent operator-configuration schema\\.[^\\r\\n]*\\R"
                        + "config-version: 5\\R",
                ""
        );
        Files.writeString(config, unversioned, StandardCharsets.UTF_8);

        ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, template);

        assertEquals(ConfigLifecycle.State.UPGRADED, result.state());
        String migrated = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(migrated.contains("# Direct block entries"));
        assertTrue(migrated.contains("&simple_blocks"));
        assertTrue(migrated.contains("*simple_blocks"));
        assertEquals(load(template).getList("gates.restrictedBlocks"),
                result.snapshot().configuration().getList("gates.restrictedBlocks"));
    }

    @Test
    void physicalMarkerRulesAndYamlNullsRefuseEveryAmbiguousDocumentUnchanged()
            throws Exception {
        List<String> refused = List.of(
                "config-version:\n",
                "config-version: null\n",
                "config-version: ~\n",
                "config-version: 1\nconfig-version: 1\n",
                "config-version: 1\n'config-version': 1\n",
                "\"config-version\": 1\n",
                "\"config\\u002dversion\": 1\n",
                "!!str config-version: 1\n",
                "&marker config-version: 1\n",
                "config-version: '1'\n",
                "config-version: !!int 1\n",
                "config-version: &schema 1\n",
                "schema: &schema 1\nconfig-version: *schema\n",
                "schema-key: &schema-key config-version\n*schema-key: 1\n",
                "config-version: -1\n",
                "config-version: +1\n",
                "config-version: 01\n",
                "config-version: 1.0\n",
                "config-version: nope\n",
                "config-version: 6\n",
                "config-version: 1\ndpc-api:\n  key:\n",
                "config-version: 1\nitems: [one, null]\n",
                "config-version: 1\n1: credential-shaped-value\n",
                "config-version: 1\n? [complex, key]\n: credential-shaped-value\n",
                "config-version: 1\nplayers: [unterminated\n"
        );

        for (int index = 0; index < refused.size(); index++) {
            Path caseDirectory = temporaryDirectory.resolve("case-" + index);
            Files.createDirectories(caseDirectory);
            Path config = caseDirectory.resolve("config.yml");
            byte[] original = refused.get(index).getBytes(StandardCharsets.UTF_8);
            Files.write(config, original);

            ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, bundledTemplate());

            assertFalse(result.compatible(), "case " + index + " should be refused");
            assertArrayEquals(original, Files.readAllBytes(config), "case " + index);
            assertNull(result.backup(), "case " + index);
            try (var files = Files.list(caseDirectory)) {
                assertEquals(1L, files.count(), "case " + index + " must not write artifacts");
            }
        }
    }

    @Test
    void flowRootPlainMarkerIsAcceptedWithoutRewriting() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        byte[] flow = "{config-version: 5, database: {password: flow-secret}, extension: {x: 3}}\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(config, flow);

        ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, bundledTemplate());

        assertEquals(ConfigLifecycle.State.CURRENT, result.state());
        assertEquals("flow-secret", result.snapshot().configuration().getString("database.password"));
        assertEquals(3, result.snapshot().configuration().getInt("extension.x"));
        assertArrayEquals(flow, Files.readAllBytes(config));
    }

    @Test
    void flowRootWithoutMarkerIsOrdinarySchemaZero() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        byte[] flow = "{language: en-US, players: {initialPower: 11}, extension: {x: 3}}\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(config, flow);

        ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, bundledTemplate());

        assertEquals(ConfigLifecycle.State.UPGRADED, result.state());
        assertEquals(0, result.sourceVersion());
        assertEquals(11, result.snapshot().configuration().getInt("players.initialPower"));
        assertEquals(3, result.snapshot().configuration().getInt("extension.x"));
        assertNull(result.backup());
    }

    @Test
    void commentOnlyInstalledFileIsSchemaZeroAndMigratesNormally() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        byte[] comments = "# an operator deliberately cleared every explicit setting\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(config, comments);

        ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, bundledTemplate());

        assertEquals(ConfigLifecycle.State.UPGRADED, result.state(), result.detail());
        assertEquals(0, result.sourceVersion());
        assertEquals(5, result.snapshot().configuration().getInt("config-version"));
        assertNull(result.backup());
    }

    @Test
    void knownSectionReplacedByScalarIsRejectedBeforeWrite() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        byte[] invalid = "players: credential-shaped-scalar\n".getBytes(StandardCharsets.UTF_8);
        Files.write(config, invalid);

        ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, bundledTemplate());

        assertEquals(ConfigLifecycle.State.INVALID, result.state());
        assertEquals("invalid configuration at players", result.detail());
        assertFalse(result.detail().contains("credential-shaped-scalar"));
        assertNull(result.backup());
        assertArrayEquals(invalid, Files.readAllBytes(config));
    }

    @Test
    void currentKnownLeafWithWrongPhysicalTypeIsValueSafeAndUnchanged() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        byte[] invalid = (
                "config-version: 5\n" +
                    "database:\n" +
                    "  password:\n" +
                    "    leaked-child: credential-shaped-value\n"
                ).getBytes(StandardCharsets.UTF_8);
        Files.write(config, invalid);

        ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, bundledTemplate());

        assertEquals(ConfigLifecycle.State.INVALID, result.state());
        assertEquals("invalid configuration at database.password", result.detail());
        assertFalse(result.detail().contains("credential-shaped-value"));
        assertArrayEquals(invalid, Files.readAllBytes(config));
        assertNull(result.backup());
    }

    @Test
    void schemaZeroEmptyMapOverKnownScalarKeepsItsTypeAndIsRejectedUnchanged()
            throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        byte[] invalid = "language: {}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(config, invalid);

        ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, bundledTemplate());

        assertEquals(ConfigLifecycle.State.INVALID, result.state());
        assertEquals("invalid configuration at language", result.detail());
        assertArrayEquals(invalid, Files.readAllBytes(config));
        assertNull(result.backup());
    }

    @Test
    void knownStringListCannotSilentlyDropMappingEntries() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        byte[] invalid = (
                "config-version: 5\n" +
                    "factions:\n" +
                    "  blockedClaimWorlds:\n" +
                    "  - valid-world\n" +
                    "  - credential-shaped-key: credential-shaped-value\n"
                ).getBytes(StandardCharsets.UTF_8);
        Files.write(config, invalid);

        ConfigLifecycle.Result result = ConfigLifecycle.prepare(config, bundledTemplate());

        assertEquals(ConfigLifecycle.State.INVALID, result.state());
        assertEquals("invalid configuration at factions.blockedClaimWorlds", result.detail());
        assertFalse(result.detail().contains("credential-shaped"));
        assertArrayEquals(invalid, Files.readAllBytes(config));
        assertNull(result.backup());
    }

    @Test
    void failedAtomicReplacementLeavesSourceAndCreatesNoBackup()
            throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        byte[] legacy = "database:\n  password: atomic-failure-secret\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(config, legacy);

        ConfigLifecycle.Result result = ConfigLifecycle.prepare(
                config,
                bundledTemplate(),
                (target, contents, expected) -> {
                    throw new IOException("credential-shaped-writer-error");
                }
        );

        assertEquals(ConfigLifecycle.State.ERROR, result.state());
        assertArrayEquals(legacy, Files.readAllBytes(config));
        assertNull(result.backup());
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(1L, files.count(), "a failed migration must not create a backup");
        }
        assertFalse(result.detail().contains("atomic-failure-secret"));
        assertFalse(result.detail().contains("credential-shaped-writer-error"));
    }

    @Test
    void freshInstallRaceNeverOverwritesAnArrivingOperatorFile() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        byte[] arriving = "config-version: 5\nsecret: arriving-secret\n"
                .getBytes(StandardCharsets.UTF_8);

        ConfigLifecycle.Result result = ConfigLifecycle.prepare(
                config,
                bundledTemplate(),
                (target, contents, expected) -> {
                    Files.write(target, arriving);
                    ConfigLifecycle.writeUtf8AtomicRequired(target, contents, expected);
                }
        );

        assertEquals(ConfigLifecycle.State.ERROR, result.state());
        assertArrayEquals(arriving, Files.readAllBytes(config));
        assertFalse(result.detail().contains("arriving-secret"));
        assertNull(result.snapshot());
    }

    @Test
    void postWriteRaceIsRereadExactlyAndNeverActivated() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        byte[] legacy = "players:\n  initialPower: 9\n".getBytes(StandardCharsets.UTF_8);
        byte[] raced = "config-version: 5\ndatabase:\n  password: raced-secret\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(config, legacy);

        ConfigLifecycle.Result result = ConfigLifecycle.prepare(
                config,
                bundledTemplate(),
                (target, contents, expected) -> {
                    ConfigLifecycle.writeUtf8AtomicRequired(target, contents, expected);
                    Files.write(target, raced);
                }
        );

        assertEquals(ConfigLifecycle.State.ERROR, result.state());
        assertNull(result.snapshot());
        assertArrayEquals(raced, Files.readAllBytes(config));
        assertNull(result.backup());
        assertFalse(result.detail().contains("raced-secret"));
    }

    @Test
    void pluginOwnedUpdatePublishesOneExactGenerationAndUsesOwnerOnlyReplacement()
            throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        ConfigLifecycle.Result prepared = ConfigLifecycle.prepare(config, bundledTemplate());

        ConfigLifecycle.Result updated = ConfigLifecycle.update(
                config,
                bundledTemplate(),
                prepared.snapshot(),
                Map.of("dpc-api.enabled", true, "dpc-api.discord-link", "https://discord.gg/test")
        );

        assertTrue(updated.compatible());
        assertTrue(updated.snapshot().configuration().getBoolean("dpc-api.enabled"));
        assertEquals("https://discord.gg/test",
                updated.snapshot().configuration().getString("dpc-api.discord-link"));
        assertArrayEquals(Files.readAllBytes(config), updated.snapshot().fileBytes());
        assertOwnerOnly(config);
        String updatedText = Files.readString(config, StandardCharsets.UTF_8);
        assertTrue(updatedText.contains("# Direct block entries"));
        assertTrue(updatedText.contains("&simple_blocks"));
        assertTrue(updatedText.contains("*simple_blocks"));
    }

    @Test
    void pluginOwnedUpdateInSparseCurrentFilePlacesKnownSectionBeforeExtensions()
            throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        Files.writeString(
                config,
                "config-version: 5\nthird-party-extension:\n  retained: true\n",
                StandardCharsets.UTF_8
        );
        ConfigLifecycle.Result prepared = ConfigLifecycle.prepare(config, bundledTemplate());

        ConfigLifecycle.Result updated = ConfigLifecycle.update(
                config,
                bundledTemplate(),
                prepared.snapshot(),
                Map.of("dpc-api.enabled", true)
        );

        assertTrue(updated.compatible());
        List<String> rootKeys = new ArrayList<>(updated.snapshot().configuration().getKeys(false));
        assertTrue(rootKeys.indexOf("dpc-api") < rootKeys.indexOf("third-party-extension"));
        assertTrue(updated.snapshot().configuration()
                .getBoolean("third-party-extension.retained"));
    }

    @Test
    void racedPluginOwnedUpdateKeepsPhysicalAndRuntimeLastKnownGood() throws Exception {
        Path config = temporaryDirectory.resolve("config.yml");
        ConfigLifecycle.Result prepared = ConfigLifecycle.prepare(config, bundledTemplate());
        byte[] operatorEdit = (
                "config-version: 5\n" +
                    "dpc-api:\n" +
                    "  enabled: false\n" +
                    "  key: operator-new-secret\n"
                ).getBytes(StandardCharsets.UTF_8);
        Files.write(config, operatorEdit);

        ConfigLifecycle.Result refused = ConfigLifecycle.update(
                config,
                bundledTemplate(),
                prepared.snapshot(),
                Map.of("dpc-api.enabled", true)
        );

        assertFalse(refused.compatible());
        assertArrayEquals(operatorEdit, Files.readAllBytes(config));
        assertFalse(prepared.snapshot().configuration().getBoolean("dpc-api.enabled"));
        assertFalse(refused.detail().contains("operator-new-secret"));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(1L, files.count(), "a refused CAS must remove its owner-only temp file");
        }
    }

    @Test
    void historicalMf4TriggerRemainsSeparateFromOrdinarySchemaZeroAdoption() throws Exception {
        Path mf4 = temporaryDirectory.resolve("mf4.yml");
        Files.writeString(mf4, "version: v4.6.0\ninitialPowerLevel: 5\n",
                StandardCharsets.UTF_8);
        Path mf5 = temporaryDirectory.resolve("mf5.yml");
        Files.write(mf5, resourceBytes("/config/historical-unversioned.yml"));

        assertTrue(ConfigLifecycle.isMf4Legacy(mf4));
        assertFalse(ConfigLifecycle.isMf4Legacy(mf5));
    }

    @Test
    void historicalMf4BackupMovesExactCredentialFileAndRestrictsItToOwner()
            throws Exception {
        Path dataFolder = temporaryDirectory.resolve("mf4-data");
        Files.createDirectories(dataFolder);
        byte[] legacy = (
                "version: v4.6.0\n" +
                    "databasePassword: historical-mf4-secret\n"
                ).getBytes(StandardCharsets.UTF_8);
        Files.write(dataFolder.resolve("config.yml"), legacy);
        Files.writeString(dataFolder.resolve("factions.json"), "[]", StandardCharsets.UTF_8);
        MedievalFactions plugin = mock(MedievalFactions.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("mf4-backup-test"));

        new MfLegacyDataMigrator(plugin).backup();

        Path backup = dataFolder.resolve("mf4_backup/config.yml");
        assertArrayEquals(legacy, Files.readAllBytes(backup));
        assertOwnerOnly(backup);
        assertFalse(Files.exists(dataFolder.resolve("config.yml")));
        assertTrue(Files.exists(dataFolder.resolve("mf4_backup/factions.json")));
    }

    private static void assertKnownKeysPrecedeExtensions(ConfigurationSection template,
                                                         ConfigurationSection actual) {
        List<String> expectedKeys = new ArrayList<>(template.getKeys(false));
        List<String> actualKeys = new ArrayList<>(actual.getKeys(false));
        assertTrue(actualKeys.size() >= expectedKeys.size());
        assertEquals(expectedKeys, actualKeys.subList(0, expectedKeys.size()));
        for (String key : expectedKeys) {
            ConfigurationSection expectedChild = template.getConfigurationSection(key);
            ConfigurationSection actualChild = actual.getConfigurationSection(key);
            if (expectedChild != null && actualChild != null) {
                assertKnownKeysPrecedeExtensions(expectedChild, actualChild);
            }
        }
    }

    private static void assertOwnerOnly(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file,
                PosixFileAttributeView.class);
        if (posix != null) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    posix.readAttributes().permissions());
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        assertNotNull(acl, "filesystem should expose POSIX permissions or ACLs");
        assertFalse(acl.getAcl().isEmpty());
        var owner = Files.getOwner(file);
        assertTrue(acl.getAcl().stream()
                .allMatch(entry -> entry.principal().equals(owner)));
    }

    private static String bundledTemplate() throws IOException {
        return new String(resourceBytes("/config.yml"), StandardCharsets.UTF_8)
                .replace("@version@", "5.9.0-test");
    }

    private static byte[] resourceBytes(String name) throws IOException {
        try (InputStream input = ConfigLifecycleTest.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("missing test resource " + name);
            }
            return input.readAllBytes();
        }
    }

    private static YamlConfiguration load(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        yaml.loadFromString(text);
        return yaml;
    }
}
