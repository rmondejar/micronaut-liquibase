/*
 * Copyright 2017-2018 original authors
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

package io.micronaut.configuration.dbmigration.liquibase;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.StartupEvent;
import io.micronaut.core.util.StringUtils;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.scheduling.annotation.Async;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.configuration.GlobalConfiguration;
import liquibase.configuration.LiquibaseConfiguration;
import liquibase.database.Database;
import liquibase.database.DatabaseConnection;
import liquibase.database.DatabaseFactory;
import liquibase.database.OfflineConnection;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.DatabaseException;
import liquibase.exception.LiquibaseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Singleton;
import javax.sql.DataSource;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;

/**
 * Synchronous listener for  {@link io.micronaut.context.event.StartupEvent} to run liquibase operations.
 *
 * @author Sergio del Amo
 * @since 1.1
 */
@Requires(classes = Liquibase.class)
@Singleton
class LiquibaseStartupEventListener {
    private static final Logger LOG = LoggerFactory.getLogger(LiquibaseStartupEventListener.class);

    private final ResourceAccessor resourceAccessor;
    private final Collection<LiquibaseConfigurationProperties> liquibaseConfigurationProperties;

    /**
     * @param resourceAccessor                 An implementation of {@link liquibase.resource.ResourceAccessor}.
     * @param liquibaseConfigurationProperties Collection of Liquibase Configurations
     */
    public LiquibaseStartupEventListener(ResourceAccessor resourceAccessor, Collection<LiquibaseConfigurationProperties> liquibaseConfigurationProperties) {
        this.resourceAccessor = resourceAccessor;
        this.liquibaseConfigurationProperties = liquibaseConfigurationProperties;
    }

    /**
     * Runs Liquibase for the datasource where there is a liquibase configuration available.
     *
     * @param event Server startup event
     */
    @EventListener
    public void onStartup(StartupEvent event) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Executing synchronous liquibase migrations");
        }
        run(false);
    }

    /**
     * Runs Liquibase asynchronously for the datasource where there is a liquibase configuration available.
     *
     * @param event Server startup event
     */
    @Async
    @EventListener
    public void onStartupAsync(StartupEvent event) {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Executing asynchronous liquibase migrations");
        }
        run(true);
    }

    /**
     * Runs Liquibase for the datasource where there is a liquibase configuration available.
     *
     * @param async if true only liquibase configurations set to async are run.
     */
    public void run(boolean async) {
        liquibaseConfigurationProperties
                .stream()
                .filter(c -> c.getDataSource() != null)
                .filter(c -> c.isEnabled())
                .filter(c -> c.isAsync() == async)
                .forEach(this::runLiquibaseForDataSourceWithConfig);
    }

    /**
     * Performs liquibase update for the given data datasource and configuration.
     *
     * @param conf Liquibase configuration
     */
    protected void runLiquibaseForDataSourceWithConfig(LiquibaseConfigurationProperties conf) {
        try {
            Connection c = null;
            Liquibase liquibase = null;
            DataSource dataSource = conf.getDataSource();
            try {
                c = dataSource.getConnection();
                liquibase = createLiquibase(c, conf);
                generateRollbackFile(liquibase, conf);
                performUpdate(liquibase, conf);
            } catch (LiquibaseException e) {
                if (LOG.isErrorEnabled()) {
                    LOG.error("LiquibaseException: ", e);
                }
            } catch (SQLException e) {
                throw new DatabaseException(e);
            } finally {
                Database database = null;
                if (liquibase != null) {
                    database = liquibase.getDatabase();
                }
                if (database != null) {
                    database.close();
                }
            }

        } catch (DatabaseException e) {
            if (LOG.isErrorEnabled()) {
                LOG.error("DatabaseException:", e);
            }
        }
    }

    /**
     * Performs Liquibase update.
     *
     * @param liquibase Primary facade class for interacting with Liquibase.
     * @param conf      Liquibase configuration
     * @throws LiquibaseException Liquibase exception.
     */
    protected void performUpdate(Liquibase liquibase, LiquibaseConfigurationProperties conf) throws LiquibaseException {
        LabelExpression labelExpression = new LabelExpression(conf.getLabels());
        Contexts contexts = new Contexts(conf.getContexts());
        if (conf.isTestRollbackOnUpdate()) {
            if (conf.getTag() != null) {
                liquibase.updateTestingRollback(conf.getTag(), contexts, labelExpression);
            } else {
                liquibase.updateTestingRollback(contexts, labelExpression);
            }
        } else {
            if (conf.getTag() != null) {
                liquibase.update(conf.getTag(), contexts, labelExpression);
            } else {
                liquibase.update(contexts, labelExpression);
            }
        }
    }

    /**
     * Generates Rollback file.
     *
     * @param liquibase Primary facade class for interacting with Liquibase.
     * @param conf      Liquibase configuration
     * @throws LiquibaseException Liquibase exception.
     */
    protected void generateRollbackFile(Liquibase liquibase, LiquibaseConfigurationProperties conf) throws LiquibaseException {
        if (conf.getRollbackFile() != null) {
            String outputEncoding = LiquibaseConfiguration.getInstance().getConfiguration(GlobalConfiguration.class).getOutputEncoding();
            try (FileOutputStream fileOutputStream = new FileOutputStream(conf.getRollbackFile());
                 Writer output = new OutputStreamWriter(fileOutputStream, outputEncoding)) {
                Contexts contexts = new Contexts(conf.getContexts());
                LabelExpression labelExpression = new LabelExpression(conf.getLabels());
                if (conf.getTag() != null) {
                    liquibase.futureRollbackSQL(conf.getTag(), contexts, labelExpression, output);
                } else {
                    liquibase.futureRollbackSQL(contexts, labelExpression, output);
                }
            } catch (IOException e) {
                throw new LiquibaseException("Unable to generate rollback file.", e);
            }
        }
    }

    /**
     * @param connection Connection with the data source
     * @param conf       Liquibase Configuration for the Data source
     * @return A Liquibase object
     * @throws LiquibaseException A liquibase exception.
     */
    protected Liquibase createLiquibase(Connection connection, LiquibaseConfigurationProperties conf) throws LiquibaseException {
        String changeLog = conf.getChangeLog();
        Liquibase liquibase = new Liquibase(changeLog, resourceAccessor, createDatabase(connection, resourceAccessor, conf));
        liquibase.setIgnoreClasspathPrefix(conf.isIgnoreClasspathPrefix());
        if (conf.getParameters() != null) {
            for (Map.Entry<String, String> entry : conf.getParameters().entrySet()) {
                liquibase.setChangeLogParameter(entry.getKey(), entry.getValue());
            }
        }

        if (conf.isDropFirst()) {
            liquibase.dropAll();
        }

        return liquibase;
    }

    /**
     * Subclasses may override this method add change some database settings such as
     * default schema before returning the database object.
     *
     * @param connection       Connection with the data source
     * @param resourceAccessor Abstraction of file access
     * @param conf             Liquibase Configuration for the Data source
     * @return a Database implementation retrieved from the {@link DatabaseFactory}.
     * @throws DatabaseException A Liquibase Database exception.
     */
    protected Database createDatabase(Connection connection,
                                      liquibase.resource.ResourceAccessor resourceAccessor,
                                      LiquibaseConfigurationProperties conf) throws DatabaseException {

        DatabaseConnection liquibaseConnection;
        if (connection == null) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Null connection returned by liquibase datasource. Using offline unknown database");
            }
            liquibaseConnection = new OfflineConnection("offline:unknown", resourceAccessor);

        } else {
            liquibaseConnection = new JdbcConnection(connection);
        }

        Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(liquibaseConnection);
        if (StringUtils.trimToNull(conf.getDefaultSchema()) != null) {
            if (database.supportsSchemas()) {
                database.setDefaultSchemaName(conf.getDefaultSchema());
            } else if (database.supportsCatalogs()) {
                database.setDefaultCatalogName(conf.getDefaultSchema());
            }
        }
        if (StringUtils.trimToNull(conf.getLiquibaseSchema()) != null) {
            if (database.supportsSchemas()) {
                database.setLiquibaseSchemaName(conf.getLiquibaseSchema());
            } else if (database.supportsCatalogs()) {
                database.setLiquibaseCatalogName(conf.getLiquibaseSchema());
            }
        }
        if (StringUtils.trimToNull(conf.getLiquibaseTablespace()) != null && database.supportsTablespaces()) {
            database.setLiquibaseTablespaceName(conf.getLiquibaseTablespace());
        }
        if (StringUtils.trimToNull(conf.getDatabaseChangeLogTable()) != null) {
            database.setDatabaseChangeLogTableName(conf.getDatabaseChangeLogTable());
        }
        if (StringUtils.trimToNull(conf.getDatabaseChangeLogLockTable()) != null) {
            database.setDatabaseChangeLogLockTableName(conf.getDatabaseChangeLogLockTable());
        }
        return database;
    }
}
