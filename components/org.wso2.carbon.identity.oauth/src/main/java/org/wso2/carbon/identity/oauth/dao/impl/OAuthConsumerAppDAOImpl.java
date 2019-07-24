/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
package org.wso2.carbon.identity.oauth.dao.impl;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.core.util.IdentityDatabaseUtil;
import org.wso2.carbon.identity.core.util.IdentityTenantUtil;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.oauth.common.exception.InvalidOAuthClientException;
import org.wso2.carbon.identity.oauth.config.OAuthServerConfiguration;
import org.wso2.carbon.identity.oauth.dao.OAuthAppDO;
import org.wso2.carbon.identity.oauth.dao.OAuthConsumerAppDAO;
import org.wso2.carbon.identity.oauth.dao.SQLQueries;
import org.wso2.carbon.identity.oauth.exception.OAuthConsumerAppException;
import org.wso2.carbon.identity.oauth.tokenprocessor.PlainTextPersistenceProcessor;
import org.wso2.carbon.identity.oauth.tokenprocessor.TokenPersistenceProcessor;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;
import org.wso2.carbon.identity.oauth2.internal.OAuth2ServiceComponentHolder;
import org.wso2.carbon.identity.oauth2.util.OAuth2Util;
import org.wso2.carbon.user.api.UserRealm;
import org.wso2.carbon.user.api.UserStoreException;
import org.wso2.carbon.user.core.util.UserCoreUtil;
import org.wso2.carbon.utils.DBUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.wso2.carbon.identity.oauth.common.OAuthConstants.OIDCConfigProperties.BACK_CHANNEL_LOGOUT_URL;
import static org.wso2.carbon.identity.oauth.common.OAuthConstants.OIDCConfigProperties.BYPASS_CLIENT_CREDENTIALS;
import static org.wso2.carbon.identity.oauth.common.OAuthConstants.OIDCConfigProperties.FRONT_CHANNEL_LOGOUT_URL;
import static org.wso2.carbon.identity.oauth.common.OAuthConstants.OIDCConfigProperties.ID_TOKEN_ENCRYPTED;
import static org.wso2.carbon.identity.oauth.common.OAuthConstants.OIDCConfigProperties.ID_TOKEN_ENCRYPTION_ALGORITHM;
import static org.wso2.carbon.identity.oauth.common.OAuthConstants.OIDCConfigProperties.ID_TOKEN_ENCRYPTION_METHOD;
import static org.wso2.carbon.identity.oauth.common.OAuthConstants.OIDCConfigProperties.RENEW_REFRESH_TOKEN;
import static org.wso2.carbon.identity.oauth.common.OAuthConstants.OIDCConfigProperties.REQUEST_OBJECT_SIGNED;
import static org.wso2.carbon.identity.oauth.common.OAuthConstants.OIDCConfigProperties.TOKEN_TYPE;
import static org.wso2.carbon.identity.oauth2.util.OAuth2Util.OPENID_CONNECT_AUDIENCE;

/**
 * Default implementation of {@link OAuthConsumerAppDAO}. This handles OAuth consumer application related DB operations.
 * <p>
 * This is an improved version of {@link org.wso2.carbon.identity.oauth.dao.OAuthAppDAO} and
 * {@link org.wso2.carbon.identity.oauth.dao.OAuthConsumerDAO}
 */
public class OAuthConsumerAppDAOImpl implements OAuthConsumerAppDAO {

    private static final Log log = LogFactory.getLog(OAuthConsumerAppDAOImpl.class);
    private static final String USERNAME = "USERNAME";
    private static final String LOWER_USERNAME = "LOWER(USERNAME)";
    private TokenPersistenceProcessor persistenceProcessor;
    private boolean isHashDisabled = OAuth2Util.isHashDisabled();

    public OAuthConsumerAppDAOImpl() {

        try {
            persistenceProcessor = OAuthServerConfiguration.getInstance().getPersistenceProcessor();
        } catch (IdentityOAuth2Exception e) {
            log.error("Error retrieving TokenPersistenceProcessor. Defaulting to PlainTextPersistenceProcessor");
            persistenceProcessor = new PlainTextPersistenceProcessor();
        }

    }

    @Override
    public void addOAuthConsumerApplication(OAuthAppDO consumerAppDO) throws OAuthConsumerAppException {

        int spTenantId = IdentityTenantUtil.getTenantId(consumerAppDO.getAppOwner().getTenantDomain());
        String userStoreDomain = consumerAppDO.getAppOwner().getUserStoreDomain();
        if (!isDuplicateApplication(consumerAppDO.getApplicationName(), consumerAppDO.getAppOwner().getUserName(),
                spTenantId, userStoreDomain)) {
            int appId = 0;
            try (Connection connection = IdentityDatabaseUtil.getDBConnection()) {
                String processedClientId =
                        persistenceProcessor.getProcessedClientId(consumerAppDO.getOauthConsumerKey());
                String processedClientSecret =
                        persistenceProcessor.getProcessedClientSecret(consumerAppDO.getOauthConsumerSecret());
                String dbProductName = connection.getMetaData().getDatabaseProductName();
                try (PreparedStatement prepStmt = connection.prepareStatement(SQLQueries.OAuthAppDAOSQLQueries
                        .ADD_OAUTH_APP_WITH_PKCE, new String[]{DBUtils.getConvertedAutoGeneratedColumnName
                        (dbProductName, "ID")})) {
                    prepStmt.setString(1, processedClientId);
                    prepStmt.setString(2, processedClientSecret);
                    prepStmt.setString(3, consumerAppDO.getAppOwner().getUserName());
                    prepStmt.setInt(4, spTenantId);
                    prepStmt.setString(5, userStoreDomain);
                    prepStmt.setString(6, consumerAppDO.getApplicationName());
                    prepStmt.setString(7, consumerAppDO.getOauthVersion());
                    prepStmt.setString(8, consumerAppDO.getCallbackUrl());
                    prepStmt.setString(9, consumerAppDO.getGrantTypes());
                    prepStmt.setString(10, consumerAppDO.isPkceMandatory() ? "1" : "0");
                    prepStmt.setString(11, consumerAppDO.isPkceSupportPlain() ? "1" : "0");
                    prepStmt.setLong(12, consumerAppDO.getUserAccessTokenExpiryTime());
                    prepStmt.setLong(13, consumerAppDO.getApplicationAccessTokenExpiryTime());
                    prepStmt.setLong(14, consumerAppDO.getRefreshTokenExpiryTime());
                    prepStmt.setLong(15, consumerAppDO.getIdTokenExpiryTime());
                    prepStmt.execute();
                    try (ResultSet results = prepStmt.getGeneratedKeys()) {
                        if (results.next()) {
                            appId = results.getInt(1);
                        }
                    }
                }

                // Some JDBC Drivers returns this in the result, some don't so need to check before continuing.
                if (appId == 0) {
                    if (log.isDebugEnabled()) {
                        log.debug("JDBC Driver did not returning the app id of the newly created app " +
                                consumerAppDO.getApplicationName() + ". So executing select operation to get the id");
                    }
                    appId = getAppIdByClientId(connection, consumerAppDO.getOauthConsumerKey());
                }

                addScopeValidators(connection, appId, consumerAppDO.getScopeValidators());
                // Handle OIDC Related Properties. These are persisted in IDN_OIDC_PROPERTY table.
                addServiceProviderOIDCProperties(connection, consumerAppDO, processedClientId, spTenantId);
                connection.commit();

            } catch (SQLException e) {
                throw new OAuthConsumerAppException("Error when executing SQL to create OAuth app " +
                        consumerAppDO.getApplicationName(), e);
            } catch (IdentityOAuth2Exception e) {
                throw new OAuthConsumerAppException("Error occurred while processing client credentials", e);
            }
        } else {
            String message = "Error when adding the application. An application with the same name already exists.";
            throw new OAuthConsumerAppException(message);
        }
    }

    @Override
    public OAuthAppDO getAppInformationByConsumerKey(String consumerKey) throws OAuthConsumerAppException {

        OAuthAppDO oauthApp = null;
        try (Connection connection = IdentityDatabaseUtil.getDBConnection()) {
            String sqlQuery = SQLQueries.OAuthAppDAOSQLQueries.GET_APP_INFO_WITH_PKCE;

            try (PreparedStatement prepStmt = connection.prepareStatement(sqlQuery)) {
                String preprocessedClientId = persistenceProcessor.getProcessedClientId(consumerKey);
                prepStmt.setString(1, preprocessedClientId);

                try (ResultSet rSet = prepStmt.executeQuery()) {
                    /*
                      We need to determine whether the result set has more than 1 row. Meaning, we found an
                      application for
                      the given consumer key. There can be situations where a user passed a key which doesn't yet
                      have an
                      associated application. We need to barf with a meaningful error message for this case
                    */
                    boolean appExists = false;
                    while (rSet.next()) {
                        // There is at least one application associated with a given key
                        appExists = true;
                        if (rSet.getString(4) != null && rSet.getString(4).length() > 0) {
                            oauthApp = new OAuthAppDO();
                            oauthApp.setOauthConsumerKey(consumerKey);
                            if (isHashDisabled) {
                                oauthApp.setOauthConsumerSecret(persistenceProcessor.getPreprocessedClientSecret(rSet
                                        .getString(1)));
                            } else {
                                oauthApp.setOauthConsumerSecret(rSet.getString(1));
                            }
                            AuthenticatedUser authenticatedUser = new AuthenticatedUser();
                            authenticatedUser.setUserName(rSet.getString(2));
                            oauthApp.setApplicationName(rSet.getString(3));
                            oauthApp.setOauthVersion(rSet.getString(4));
                            oauthApp.setCallbackUrl(rSet.getString(5));
                            authenticatedUser.setTenantDomain(IdentityTenantUtil.getTenantDomain(rSet.getInt(6)));
                            authenticatedUser.setUserStoreDomain(rSet.getString(7));
                            oauthApp.setAppOwner(authenticatedUser);
                            oauthApp.setGrantTypes(rSet.getString(8));
                            oauthApp.setId(rSet.getInt(9));
                            oauthApp.setPkceMandatory(!"0".equals(rSet.getString(10)));
                            oauthApp.setPkceSupportPlain(!"0".equals(rSet.getString(11)));
                            oauthApp.setUserAccessTokenExpiryTime(rSet.getLong(12));
                            oauthApp.setApplicationAccessTokenExpiryTime(rSet.getLong(13));
                            oauthApp.setRefreshTokenExpiryTime(rSet.getLong(14));
                            oauthApp.setIdTokenExpiryTime(rSet.getLong(15));
                            oauthApp.setState(rSet.getString(16));

                            String spTenantDomain = authenticatedUser.getTenantDomain();
                            handleServiceProviderOIDCProperties(connection, preprocessedClientId, spTenantDomain,
                                    oauthApp);
                            oauthApp.setScopeValidators(getScopeValidators(connection, oauthApp.getId()));
                        }
                    }

                    if (!appExists) {
                        handleRequestForANonExistingConsumerKey(consumerKey);
                    }
                    connection.commit();
                } catch (InvalidOAuthClientException e) {
                    throw new OAuthConsumerAppException("The provided consumer key: " + consumerKey + " is invalid.",
                            e);
                }
            }
        } catch (SQLException e) {
            throw new OAuthConsumerAppException("Error while retrieving the app information for consumer key: " +
                    consumerKey, e);
        } catch (IdentityOAuth2Exception e) {
            throw new OAuthConsumerAppException("Error occurred while processing client id and client secret by " +
                    "TokenPersistenceProcessor.", e);
        }
        return oauthApp;
    }

    @Override
    public OAuthAppDO getAppInformationByAppName(String appName) throws OAuthConsumerAppException {

        OAuthAppDO oauthApp;
        try (Connection connection = IdentityDatabaseUtil.getDBConnection()) {
            int tenantID = CarbonContext.getThreadLocalCarbonContext().getTenantId();
            String sqlQuery = SQLQueries.OAuthAppDAOSQLQueries.GET_APP_INFO_BY_APP_NAME_WITH_PKCE;

            try (PreparedStatement prepStmt = connection.prepareStatement(sqlQuery)) {
                prepStmt.setString(1, appName);
                prepStmt.setInt(2, tenantID);

                try (ResultSet rSet = prepStmt.executeQuery()) {
                    oauthApp = new OAuthAppDO();
                    oauthApp.setApplicationName(appName);
                    AuthenticatedUser user = new AuthenticatedUser();
                    user.setTenantDomain(IdentityTenantUtil.getTenantDomain(tenantID));
                    /*
                      We need to determine whether the result set has more than 1 row. Meaning, we found an
                      application for the given consumer key. There can be situations where a user passed a key which
                       doesn't yet have an associated application. We need to barf with a meaningful error message for
                        this case.
                     */
                    boolean appExists = false;
                    while (rSet.next()) {
                        // There is at least one application associated with a given key
                        appExists = true;
                        if (rSet.getString(4) != null && rSet.getString(4).length() > 0) {
                            if (isHashDisabled) {
                                oauthApp.setOauthConsumerSecret(persistenceProcessor.getPreprocessedClientSecret(rSet
                                        .getString(1)));
                            } else {
                                oauthApp.setOauthConsumerSecret(rSet.getString(1));
                            }
                            user.setUserName(rSet.getString(2));
                            user.setUserStoreDomain(rSet.getString(3));
                            oauthApp.setAppOwner(user);

                            String preprocessedClientId = persistenceProcessor.getPreprocessedClientId(rSet.getString
                                    (4));
                            oauthApp.setOauthConsumerKey(preprocessedClientId);
                            oauthApp.setOauthVersion(rSet.getString(5));
                            oauthApp.setCallbackUrl(rSet.getString(6));
                            oauthApp.setGrantTypes(rSet.getString(7));
                            oauthApp.setId(rSet.getInt(8));
                            oauthApp.setPkceMandatory(!"0".equals(rSet.getString(9)));
                            oauthApp.setPkceSupportPlain(!"0".equals(rSet.getString(10)));
                            oauthApp.setUserAccessTokenExpiryTime(rSet.getLong(11));
                            oauthApp.setApplicationAccessTokenExpiryTime(rSet.getLong(12));
                            oauthApp.setRefreshTokenExpiryTime(rSet.getLong(13));
                            oauthApp.setIdTokenExpiryTime(rSet.getLong(14));

                            String spTenantDomain = user.getTenantDomain();
                            handleServiceProviderOIDCProperties(connection, preprocessedClientId, spTenantDomain,
                                    oauthApp);
                            oauthApp.setScopeValidators(getScopeValidators(connection, oauthApp.getId()));
                        }
                    }

                    if (!appExists) {
                        String message = "Cannot find an application associated with the given appName : " + appName;
                        if (log.isDebugEnabled()) {
                            log.debug(message);
                        }
                        throw new OAuthConsumerAppException(message);
                    }
                    connection.commit();
                } catch (IdentityOAuth2Exception e) {
                    throw new OAuthConsumerAppException("Error occurred while processing client id or client secret " +
                            "by TokenPersistenceProcessor", e);
                }
            }
        } catch (SQLException e) {
            throw new OAuthConsumerAppException("Error while retrieving the app information of the give app name: " +
                    appName, e);
        }
        return oauthApp;
    }

    @Override
    public OAuthAppDO[] getOAuthConsumerAppsOfUser(String username,
                                                   int tenantId) throws OAuthConsumerAppException {

        OAuthAppDO[] oauthAppsOfUser;
        try (Connection connection = IdentityDatabaseUtil.getDBConnection()) {
            String sql = SQLQueries.OAuthAppDAOSQLQueries.GET_CONSUMER_APPS_OF_USER_WITH_PKCE;

            if (!isUsernameCaseSensitive(username, tenantId)) {
                sql = sql.replace(USERNAME, LOWER_USERNAME);
            }
            try (PreparedStatement prepStmt = connection.prepareStatement(sql)) {
                prepStmt.setString(1, UserCoreUtil.removeDomainFromName(username));
                prepStmt.setString(2, IdentityUtil.extractDomainFromName(username));
                prepStmt.setInt(3, tenantId);

                try (ResultSet rSet = prepStmt.executeQuery()) {
                    List<OAuthAppDO> oauthApps = new ArrayList<>();
                    while (rSet.next()) {
                        if (rSet.getString(3) != null && rSet.getString(3).length() > 0) {
                            OAuthAppDO oauthApp = new OAuthAppDO();
                            String preprocessedClientId = persistenceProcessor.getPreprocessedClientId(rSet.getString
                                    (1));

                            oauthApp.setOauthConsumerKey(preprocessedClientId);
                            oauthApp.setOauthConsumerKey(persistenceProcessor.getPreprocessedClientId(rSet.getString
                                    (1)));
                            if (isHashDisabled) {
                                oauthApp.setOauthConsumerSecret(persistenceProcessor.getPreprocessedClientSecret(rSet
                                        .getString(2)));
                            }
                            oauthApp.setApplicationName(rSet.getString(3));
                            oauthApp.setOauthVersion(rSet.getString(4));
                            oauthApp.setCallbackUrl(rSet.getString(5));
                            oauthApp.setGrantTypes(rSet.getString(6));
                            oauthApp.setId(rSet.getInt(7));
                            AuthenticatedUser authenticatedUser = new AuthenticatedUser();
                            authenticatedUser.setUserName(rSet.getString(8));
                            authenticatedUser.setTenantDomain(IdentityTenantUtil
                                    .getTenantDomain(rSet.getInt(9)));
                            authenticatedUser.setUserStoreDomain(rSet.getString(10));
                            oauthApp.setPkceMandatory(!"0".equals(rSet.getString(11)));
                            oauthApp.setPkceSupportPlain(!"0".equals(rSet.getString(12)));
                            oauthApp.setUserAccessTokenExpiryTime(rSet.getLong(13));
                            oauthApp.setApplicationAccessTokenExpiryTime(rSet.getLong(14));
                            oauthApp.setRefreshTokenExpiryTime(rSet.getLong(15));
                            oauthApp.setIdTokenExpiryTime(rSet.getLong(16));
                            oauthApp.setAppOwner(authenticatedUser);
                            String spTenantDomain = authenticatedUser.getTenantDomain();
                            handleServiceProviderOIDCProperties(connection, preprocessedClientId, spTenantDomain,
                                    oauthApp);
                            oauthApp.setScopeValidators(getScopeValidators(connection, oauthApp.getId()));
                            oauthApps.add(oauthApp);
                        }
                    }
                    oauthAppsOfUser = oauthApps.toArray(new OAuthAppDO[0]);
                    connection.commit();
                }
            }
        } catch (SQLException e) {
            throw new OAuthConsumerAppException("Error occurred while retrieving OAuth consumer apps of user: " +
                    username, e);
        } catch (IdentityOAuth2Exception e) {
            throw new OAuthConsumerAppException("Error occurred while processing client id and client secret by " +
                    "TokenPersistenceProcessor", e);
        }
        return oauthAppsOfUser;
    }

    @Override
    public String getOAuthConsumerSecret(String consumerKey) throws OAuthConsumerAppException {

        String consumerSecret = null;
        if (isHashDisabled) {
            Connection connection = IdentityDatabaseUtil.getDBConnection();
            PreparedStatement prepStmt = null;
            ResultSet resultSet = null;

            try {
                prepStmt = connection.prepareStatement(SQLQueries.OAuthConsumerDAOSQLQueries.GET_CONSUMER_SECRET);
                prepStmt.setString(1, persistenceProcessor.getProcessedClientId(consumerKey));
                resultSet = prepStmt.executeQuery();

                if (resultSet.next()) {
                    consumerSecret = persistenceProcessor.getPreprocessedClientSecret(resultSet.getString(1));
                } else {
                    if (log.isDebugEnabled()) {
                        log.debug("Invalid Consumer Key : " + consumerKey);
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                throw new OAuthConsumerAppException("Error when reading the consumer secret for consumer key : " +
                        consumerKey, e);
            } catch (IdentityOAuth2Exception e) {
                throw new OAuthConsumerAppException("Error occurred while processing client id and client secret by " +
                        "TokenPersistenceProcessor", e);
            } finally {
                IdentityDatabaseUtil.closeAllConnections(connection, resultSet, prepStmt);
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Consumer secret hashing enabled. Returning client secret as null.");
            }
        }
        return consumerSecret;
    }

    @Override
    public String getConsumerApplicationOwnerName(String consumerKey) throws OAuthConsumerAppException {

        String username = "";
        Connection connection = IdentityDatabaseUtil.getDBConnection();
        PreparedStatement prepStmt = null;
        ResultSet resultSet = null;
        try {
            prepStmt = connection.prepareStatement(SQLQueries.OAuthConsumerDAOSQLQueries.GET_USERNAME_FOR_CONSUMER_KEY);
            prepStmt.setString(1, consumerKey);
            resultSet = prepStmt.executeQuery();

            if (resultSet.next()) {
                username = resultSet.getString(1);
            } else {
                log.debug("Invalid consumer key: " + consumerKey);
            }
            connection.commit();
        } catch (SQLException e) {
            log.error("Error when executing the SQL : " +
                    SQLQueries.OAuthConsumerDAOSQLQueries.GET_USERNAME_FOR_CONSUMER_KEY);
            log.error(e.getMessage(), e);
            throw new OAuthConsumerAppException("Error while reading username for consumer key: " + consumerKey);
        } finally {
            IdentityDatabaseUtil.closeAllConnections(connection, resultSet, prepStmt);
        }
        return username;
    }

    @Override
    public void updateOAuthConsumerApplication(OAuthAppDO oauthAppDO) throws OAuthConsumerAppException {

        boolean isUserValidForOwnerUpdate = validateUserForOwnerUpdate(oauthAppDO);
        try (Connection connection = IdentityDatabaseUtil.getDBConnection()) {
            String sqlQuery = getSqlQueryForUpdateConsumerApplication(isUserValidForOwnerUpdate);
            try (PreparedStatement prepStmt = connection.prepareStatement(sqlQuery)) {
                prepStmt.setString(1, oauthAppDO.getApplicationName());
                prepStmt.setString(2, oauthAppDO.getCallbackUrl());
                prepStmt.setString(3, oauthAppDO.getGrantTypes());

                if (isUserValidForOwnerUpdate) {
                    setValuesToStatementWithPKCEAndOwnerUpdate(oauthAppDO, prepStmt);
                } else {
                    setValuesToStatementWithPKCENoOwnerUpdate(oauthAppDO, prepStmt);
                }
                int count = prepStmt.executeUpdate();
                updateScopeValidators(connection, oauthAppDO.getId(), oauthAppDO.getScopeValidators());
                if (log.isDebugEnabled()) {
                    log.debug("No. of records updated for updating consumer application. : " + count);
                }

                addOrUpdateOIDCSpProperty(oauthAppDO, connection);
                connection.commit();
            }
        } catch (SQLException e) {
            throw new OAuthConsumerAppException("Error when updating OAuth application", e);
        } catch (IdentityOAuth2Exception e) {
            throw new OAuthConsumerAppException("Error occurred while processing client id and client secret by " +
                    "TokenPersistenceProcessor", e);
        }
    }

    @Override
    public void updateOAuthConsumerAppName(String consumerKey,
                                           String appName) throws OAuthConsumerAppException {

        try (Connection connection = IdentityDatabaseUtil.getDBConnection();
             PreparedStatement statement = connection.prepareStatement(SQLQueries.OAuthAppDAOSQLQueries
                     .UPDATE_OAUTH_INFO)) {
            statement.setString(1, appName);
            statement.setString(2, consumerKey);
            statement.execute();
            connection.setAutoCommit(false);
            connection.commit();
        } catch (SQLException e) {
            throw new OAuthConsumerAppException("Error while updating the application name of the OAuth application " +
                    "with consumer key: " + consumerKey, e);
        }
    }

    @Override
    public void updateOAuthConsumerSecret(String consumerKey, String consumerSecret) throws OAuthConsumerAppException {

        try (Connection connection = IdentityDatabaseUtil.getDBConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(SQLQueries.OAuthAppDAOSQLQueries
                     .UPDATE_OAUTH_SECRET_KEY)) {
            preparedStatement.setString(1, persistenceProcessor
                    .getPreprocessedClientSecret(consumerSecret));
            preparedStatement.setString(2, consumerKey);
            preparedStatement.execute();
            connection.commit();
        } catch (SQLException e) {
            throw new OAuthConsumerAppException("Error while updating the consumer key of the OAuth application " +
                    "with consumer key: " + consumerKey, e);
        } catch (IdentityOAuth2Exception e) {
            throw new OAuthConsumerAppException("Error occurred while processing client secret by " +
                    "TokenPersistenceProcessor", e);
        }

    }

    @Override
    public void updateOAuthConsumerAppState(String consumerKey, String state) throws OAuthConsumerAppException {

        try (Connection connection = IdentityDatabaseUtil.getDBConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(SQLQueries.OAuthAppDAOSQLQueries
                     .UPDATE_APPLICATION_STATE)) {
            preparedStatement.setString(1, state);
            preparedStatement.setString(2, consumerKey);
            preparedStatement.execute();
            connection.commit();
        } catch (SQLException e) {
            throw new OAuthConsumerAppException("Error while updating the state the OAuth application " +
                    "with consumer key: " + consumerKey, e);
        }
    }

    @Override
    public void removeOAuthConsumerApplication(String consumerKey) throws OAuthConsumerAppException {

        try (Connection connection = IdentityDatabaseUtil.getDBConnection(); PreparedStatement
                prepStmt = connection.prepareStatement(SQLQueries.OAuthAppDAOSQLQueries.REMOVE_APPLICATION)) {
            prepStmt.setString(1, consumerKey);
            prepStmt.execute();
            if (isOIDCAudienceEnabled()) {
                String tenantDomain = CarbonContext.getThreadLocalCarbonContext().getTenantDomain();
                removeServiceProviderOIDCProperties(connection, tenantDomain, consumerKey);
            }
            connection.commit();
        } catch (SQLException e) {
            throw new OAuthConsumerAppException("Error when executing the SQL : " + SQLQueries.OAuthAppDAOSQLQueries
                    .REMOVE_APPLICATION, e);
        }
    }

    @Override
    public void removeOIDCProperties(String consumerKey,
                                     String tenantDomain) throws OAuthConsumerAppException {

        Connection connection = IdentityDatabaseUtil.getDBConnection();
        try {
            removeServiceProviderOIDCProperties(connection, tenantDomain, consumerKey);
            connection.commit();
        } catch (SQLException e) {
            String errorMsg = "Error while removing OIDC properties for client ID: " + consumerKey + " and tenant " +
                    "domain: " + tenantDomain;
            IdentityDatabaseUtil.rollbackTransaction(connection);
            throw new OAuthConsumerAppException(errorMsg, e);
        } finally {
            IdentityDatabaseUtil.closeAllConnections(connection, null, null);
        }
    }

    private boolean isDuplicateApplication(String appName,
                                           String username,
                                           int tenantId,
                                           String userDomain) throws OAuthConsumerAppException {

        boolean isDuplicateApp = false;
        boolean isUsernameCaseSensitive = IdentityUtil.isUserStoreInUsernameCaseSensitive(username, tenantId);

        try (Connection connection = IdentityDatabaseUtil.getDBConnection()) {
            String sql = SQLQueries.OAuthAppDAOSQLQueries.CHECK_EXISTING_APPLICATION;
            if (!isUsernameCaseSensitive) {
                sql = sql.replace(USERNAME, LOWER_USERNAME);
            }
            try (PreparedStatement prepStmt = connection.prepareStatement(sql)) {
                if (isUsernameCaseSensitive) {
                    prepStmt.setString(1, username);
                } else {
                    prepStmt.setString(1, username.toLowerCase());
                }
                prepStmt.setInt(2, tenantId);
                prepStmt.setString(3, userDomain);
                prepStmt.setString(4, appName);

                try (ResultSet rSet = prepStmt.executeQuery()) {
                    if (rSet.next()) {
                        isDuplicateApp = true;
                    }
                    connection.commit();
                }
            }
        } catch (SQLException e) {
            throw new OAuthConsumerAppException("Error when executing the SQL : " + SQLQueries.OAuthAppDAOSQLQueries
                    .CHECK_EXISTING_APPLICATION, e);
        }
        return isDuplicateApp;
    }

    private int getAppIdByClientId(Connection connection,
                                   String clientId) throws OAuthConsumerAppException {

        int appId = 0;
        try (PreparedStatement prepStmt = connection.prepareStatement(SQLQueries.OAuthAppDAOSQLQueries
                .GET_APP_ID_BY_CONSUMER_KEY)) {
            prepStmt.setString(1, persistenceProcessor.getProcessedClientId(clientId));
            try (ResultSet rSet = prepStmt.executeQuery()) {
                boolean rSetHasRows = false;
                while (rSet.next()) {
                    // There is at least one application associated with a given key.
                    rSetHasRows = true;
                    appId = rSet.getInt(1);
                }
                if (!rSetHasRows) {
                    String message = "Cannot find an application associated with the given consumer key : " + clientId;
                    if (log.isDebugEnabled()) {
                        log.debug(message);
                    }
                    throw new OAuthConsumerAppException(message);
                }
            }
        } catch (SQLException e) {
            throw new OAuthConsumerAppException("Error when executing SQL query to retrieve application ID of " +
                    "consumer key " + clientId, e);
        } catch (IdentityOAuth2Exception e) {
            throw new OAuthConsumerAppException("Error occurred while processing the client id and client secret by " +
                    "TokenPersistenceProcessor");
        }
        return appId;
    }

    private boolean isUsernameCaseSensitive(String username,
                                            int tenantId) {

        return IdentityUtil.isUserStoreInUsernameCaseSensitive(username, tenantId);
    }

    private void addScopeValidators(Connection connection,
                                    int appId,
                                    String[] scopeValidators)
            throws SQLException {

        if (scopeValidators != null && scopeValidators.length > 0) {
            log.debug(String.format("Adding %d Scope validators registered for OAuth appId %d",
                    scopeValidators.length, appId));
            try (PreparedStatement stmt = connection.prepareStatement(SQLQueries.OAuthAppDAOSQLQueries
                    .ADD_APP_SCOPE_VALIDATOR)) {
                for (String scopeValidator : scopeValidators) {
                    stmt.setInt(1, appId);
                    stmt.setString(2, scopeValidator);
                    stmt.addBatch();
                }
                stmt.executeBatch();
            }
        }
    }

    private void handleRequestForANonExistingConsumerKey(String consumerKey) throws InvalidOAuthClientException {

        String message = "Cannot find an application associated with the given consumer key : " + consumerKey;
        if (log.isDebugEnabled()) {
            log.debug(message);
        }
        throw new InvalidOAuthClientException(message);
    }

    private String[] getScopeValidators(Connection connection,
                                        int id) throws SQLException {

        List<String> scopeValidators = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(SQLQueries.OAuthAppDAOSQLQueries
                .GET_APP_SCOPE_VALIDATORS)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    scopeValidators.add(rs.getString(1));
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug(String.format("Retrieving %d Scope validators registered for OAuth appId %d",
                    scopeValidators.size(), id));
        }
        return scopeValidators.toArray(new String[0]);
    }

    private void updateScopeValidators(Connection connection,
                                       int appId,
                                       String[] scopeValidators) throws SQLException {

        log.debug(String.format("Removing  Scope validators registered for OAuth appId %d", appId));
        try (PreparedStatement stmt = connection.prepareStatement(SQLQueries.OAuthAppDAOSQLQueries
                .REMOVE_APP_SCOPE_VALIDATORS)) {
            stmt.setInt(1, appId);
            stmt.execute();
        }
        addScopeValidators(connection, appId, scopeValidators);
    }

    private void addServiceProviderOIDCProperties(Connection connection,
                                                  OAuthAppDO consumerAppDO,
                                                  String processedClientId,
                                                  int spTenantId) throws SQLException {

        try (PreparedStatement prepStmtAddOIDCProperty = connection
                .prepareStatement(SQLQueries.OAuthAppDAOSQLQueries.ADD_SP_OIDC_PROPERTY)) {

            if (isOIDCAudienceEnabled() && consumerAppDO.getAudiences() != null) {
                String[] audiences = consumerAppDO.getAudiences();
                for (String audience : audiences) {
                    addToBatchForOIDCPropertyAdd(processedClientId, spTenantId, prepStmtAddOIDCProperty,
                            OPENID_CONNECT_AUDIENCE, audience);
                }
            }

            addToBatchForOIDCPropertyAdd(processedClientId, spTenantId, prepStmtAddOIDCProperty,
                    REQUEST_OBJECT_SIGNED, String.valueOf(consumerAppDO.isRequestObjectSignatureValidationEnabled()));

            addToBatchForOIDCPropertyAdd(processedClientId, spTenantId, prepStmtAddOIDCProperty,
                    ID_TOKEN_ENCRYPTED, String.valueOf(consumerAppDO.isIdTokenEncryptionEnabled()));

            addToBatchForOIDCPropertyAdd(processedClientId, spTenantId, prepStmtAddOIDCProperty,
                    ID_TOKEN_ENCRYPTION_ALGORITHM, String.valueOf(consumerAppDO.getIdTokenEncryptionAlgorithm()));

            addToBatchForOIDCPropertyAdd(processedClientId, spTenantId, prepStmtAddOIDCProperty,
                    ID_TOKEN_ENCRYPTION_METHOD, String.valueOf(consumerAppDO.getIdTokenEncryptionMethod()));

            addToBatchForOIDCPropertyAdd(processedClientId, spTenantId, prepStmtAddOIDCProperty,
                    BACK_CHANNEL_LOGOUT_URL, consumerAppDO.getBackChannelLogoutUrl());

            addToBatchForOIDCPropertyAdd(processedClientId, spTenantId, prepStmtAddOIDCProperty,
                    FRONT_CHANNEL_LOGOUT_URL, consumerAppDO.getFrontchannelLogoutUrl());

            addToBatchForOIDCPropertyAdd(processedClientId, spTenantId, prepStmtAddOIDCProperty,
                    TOKEN_TYPE, consumerAppDO.getTokenType());

            addToBatchForOIDCPropertyAdd(processedClientId, spTenantId, prepStmtAddOIDCProperty,
                    BYPASS_CLIENT_CREDENTIALS, String.valueOf(consumerAppDO.isBypassClientCredentials()));

            addToBatchForOIDCPropertyAdd(processedClientId, spTenantId, prepStmtAddOIDCProperty,
                    RENEW_REFRESH_TOKEN, consumerAppDO.getRenewRefreshTokenEnabled());

            prepStmtAddOIDCProperty.executeBatch();
        }
    }

    private void handleServiceProviderOIDCProperties(Connection connection,
                                                     String preprocessedClientId,
                                                     String spTenantDomain,
                                                     OAuthAppDO oauthApp) throws IdentityOAuth2Exception {

        Map<String, List<String>> spOIDCProperties = getServiceProviderOIDCProperties(connection,
                preprocessedClientId, spTenantDomain);

        // Set OIDC properties to IDN_OIDC_PROPERTY table.
        setServiceProviderOIDCProperties(spOIDCProperties, oauthApp);
    }

    private void setServiceProviderOIDCProperties(Map<String, List<String>> spOIDCProperties,
                                                  OAuthAppDO oauthApp) {

        // Handle OIDC audience values
        if (isOIDCAudienceEnabled() &&
                CollectionUtils.isNotEmpty(spOIDCProperties.get(OPENID_CONNECT_AUDIENCE))) {
            List<String> oidcAudience = new ArrayList<>(spOIDCProperties.get(OPENID_CONNECT_AUDIENCE));
            oauthApp.setAudiences(oidcAudience.toArray(new String[0]));
        }

        // Handle other SP OIDC properties
        boolean isRequestObjectSigned = Boolean.parseBoolean(
                getFirstPropertyValue(spOIDCProperties, REQUEST_OBJECT_SIGNED));
        oauthApp.setRequestObjectSignatureValidationEnabled(isRequestObjectSigned);

        boolean isIdTokenEncrypted = Boolean.parseBoolean(
                getFirstPropertyValue(spOIDCProperties, ID_TOKEN_ENCRYPTED));
        oauthApp.setIdTokenEncryptionEnabled(isIdTokenEncrypted);

        String idTokenEncryptionAlgorithm = getFirstPropertyValue(spOIDCProperties, ID_TOKEN_ENCRYPTION_ALGORITHM);
        oauthApp.setIdTokenEncryptionAlgorithm(idTokenEncryptionAlgorithm);

        String idTokenEncryptionMethod = getFirstPropertyValue(spOIDCProperties, ID_TOKEN_ENCRYPTION_METHOD);
        oauthApp.setIdTokenEncryptionMethod(idTokenEncryptionMethod);

        String backChannelLogoutUrl = getFirstPropertyValue(spOIDCProperties, BACK_CHANNEL_LOGOUT_URL);
        oauthApp.setBackChannelLogoutUrl(backChannelLogoutUrl);

        String frontchannelLogoutUrl = getFirstPropertyValue(spOIDCProperties, FRONT_CHANNEL_LOGOUT_URL);
        oauthApp.setFrontchannelLogoutUrl(frontchannelLogoutUrl);

        String tokenType = getFirstPropertyValue(spOIDCProperties, TOKEN_TYPE);
        oauthApp.setTokenType(tokenType);

        boolean bypassClientCreds = Boolean.parseBoolean(
                getFirstPropertyValue(spOIDCProperties, BYPASS_CLIENT_CREDENTIALS));
        oauthApp.setBypassClientCredentials(bypassClientCreds);

        String renewRefreshToken = getFirstPropertyValue(spOIDCProperties, RENEW_REFRESH_TOKEN);
        oauthApp.setRenewRefreshTokenEnabled(renewRefreshToken);
    }

    private Map<String, List<String>> getServiceProviderOIDCProperties(Connection connection,
                                                                       String consumerKey,
                                                                       String spTenantDomain)
            throws IdentityOAuth2Exception {

        Map<String, List<String>> spOIDCProperties = new HashMap<>();
        PreparedStatement prepStatement = null;
        ResultSet spOIDCPropertyResultSet = null;
        try {
            prepStatement = connection.prepareStatement(SQLQueries.OAuthAppDAOSQLQueries.GET_ALL_SP_OIDC_PROPERTIES);
            prepStatement.setInt(1, IdentityTenantUtil.getTenantId(spTenantDomain));
            prepStatement.setString(2, consumerKey);

            spOIDCPropertyResultSet = prepStatement.executeQuery();
            while (spOIDCPropertyResultSet.next()) {
                String propertyKey = spOIDCPropertyResultSet.getString(1);
                String propertyValue = spOIDCPropertyResultSet.getString(2);
                spOIDCProperties.computeIfAbsent(propertyKey, k -> new ArrayList<>()).add(propertyValue);
            }
        } catch (SQLException e) {
            String errorMsg = "Error occurred while retrieving OIDC properties for client ID: " + consumerKey +
                    " and tenant domain: " + spTenantDomain;
            throw new IdentityOAuth2Exception(errorMsg, e);
        } finally {
            IdentityDatabaseUtil.closeAllConnections(null, spOIDCPropertyResultSet, prepStatement);
        }
        return spOIDCProperties;
    }

    private void removeServiceProviderOIDCProperties(Connection connection,
                                                     String tenantDomain,
                                                     String consumerKey) throws SQLException {

        try (PreparedStatement prepStmt =
                     connection.prepareStatement(SQLQueries.OAuthAppDAOSQLQueries.REMOVE_ALL_SP_OIDC_PROPERTIES)) {
            prepStmt.setInt(1, IdentityTenantUtil.getTenantId(tenantDomain));
            prepStmt.setString(2, consumerKey);
            prepStmt.execute();
        }
    }

    private String getFirstPropertyValue(Map<String, List<String>> propertyMap,
                                         String key) {

        return CollectionUtils.isNotEmpty(propertyMap.get(key)) ? propertyMap.get(key).get(0) : null;
    }

    private void addOrUpdateOIDCSpProperty(OAuthAppDO oauthAppDO,
                                           Connection connection) throws IdentityOAuth2Exception, SQLException {

        String preprocessedClientId = persistenceProcessor.getPreprocessedClientId(oauthAppDO.getOauthConsumerKey());
        String spTenantDomain = oauthAppDO.getUser().getTenantDomain();
        int spTenantId = IdentityTenantUtil.getTenantId(spTenantDomain);

        // Get the current OIDC SP properties.
        Map<String, List<String>> spOIDCProperties =
                getServiceProviderOIDCProperties(connection, preprocessedClientId, spTenantDomain);

        // Add new entry in IDN_OIDC_PROPERTY table for each new OIDC property.
        PreparedStatement prepStatementForPropertyAdd =
                connection.prepareStatement(SQLQueries.OAuthAppDAOSQLQueries.ADD_SP_OIDC_PROPERTY);

        PreparedStatement preparedStatementForPropertyUpdate =
                connection.prepareStatement(SQLQueries.OAuthAppDAOSQLQueries.UPDATE_SP_OIDC_PROPERTY);

        PreparedStatement prepStatementForPropertyDelete =
                connection.prepareStatement(SQLQueries.OAuthAppDAOSQLQueries.REMOVE_SP_OIDC_PROPERTY);

        if (isOIDCAudienceEnabled()) {
            String[] audiences = oauthAppDO.getAudiences();
            HashSet<String> newAudiences = audiences == null ? new HashSet<>() : new HashSet<>(Arrays.asList
                    (audiences));
            List<String> oidcAudienceList = getOIDCAudiences(spTenantDomain, oauthAppDO.getOauthConsumerKey());
            Set<String> currentAudiences = oidcAudienceList == null ? new HashSet<>() : new HashSet<>(oidcAudienceList);
            HashSet<String> newAudienceClone = (HashSet<String>) newAudiences.clone();
            //removing all duplicate audiences in the new audience list
            newAudiences.removeAll(currentAudiences);
            //obtaining the audience values deleted in the list by user
            currentAudiences.removeAll(newAudienceClone);

            for (String deletedAudience : currentAudiences) {
                addToBatchForOIDCPropertyDelete(preprocessedClientId, spTenantId, prepStatementForPropertyDelete,
                        OPENID_CONNECT_AUDIENCE, deletedAudience);
            }

            for (String addedAudience : newAudiences) {
                addToBatchForOIDCPropertyAdd(preprocessedClientId, spTenantId, prepStatementForPropertyAdd,
                        OPENID_CONNECT_AUDIENCE, addedAudience);
            }
        }

        addOrUpdateOIDCSpProperty(preprocessedClientId, spTenantId, spOIDCProperties, REQUEST_OBJECT_SIGNED,
                String.valueOf(oauthAppDO.isRequestObjectSignatureValidationEnabled()),
                prepStatementForPropertyAdd, preparedStatementForPropertyUpdate);

        addOrUpdateOIDCSpProperty(preprocessedClientId, spTenantId, spOIDCProperties, ID_TOKEN_ENCRYPTED,
                String.valueOf(oauthAppDO.isIdTokenEncryptionEnabled()),
                prepStatementForPropertyAdd, preparedStatementForPropertyUpdate);

        addOrUpdateOIDCSpProperty(preprocessedClientId, spTenantId, spOIDCProperties, ID_TOKEN_ENCRYPTION_ALGORITHM,
                String.valueOf(oauthAppDO.getIdTokenEncryptionAlgorithm()),
                prepStatementForPropertyAdd, preparedStatementForPropertyUpdate);

        addOrUpdateOIDCSpProperty(preprocessedClientId, spTenantId, spOIDCProperties, ID_TOKEN_ENCRYPTION_METHOD,
                String.valueOf(oauthAppDO.getIdTokenEncryptionMethod()),
                prepStatementForPropertyAdd, preparedStatementForPropertyUpdate);

        addOrUpdateOIDCSpProperty(preprocessedClientId, spTenantId, spOIDCProperties, BACK_CHANNEL_LOGOUT_URL,
                oauthAppDO.getBackChannelLogoutUrl(), prepStatementForPropertyAdd, preparedStatementForPropertyUpdate);

        addOrUpdateOIDCSpProperty(preprocessedClientId, spTenantId, spOIDCProperties, FRONT_CHANNEL_LOGOUT_URL,
                oauthAppDO.getFrontchannelLogoutUrl(), prepStatementForPropertyAdd, preparedStatementForPropertyUpdate);

        addOrUpdateOIDCSpProperty(preprocessedClientId, spTenantId, spOIDCProperties, TOKEN_TYPE,
                oauthAppDO.getTokenType(), prepStatementForPropertyAdd, preparedStatementForPropertyUpdate);

        addOrUpdateOIDCSpProperty(preprocessedClientId, spTenantId, spOIDCProperties, BYPASS_CLIENT_CREDENTIALS,
                String.valueOf(oauthAppDO.isBypassClientCredentials()), prepStatementForPropertyAdd,
                preparedStatementForPropertyUpdate);

        addOrUpdateOIDCSpProperty(preprocessedClientId, spTenantId, spOIDCProperties, RENEW_REFRESH_TOKEN,
                oauthAppDO.getRenewRefreshTokenEnabled(), prepStatementForPropertyAdd,
                preparedStatementForPropertyUpdate);

        // Execute batched add/update/delete.
        prepStatementForPropertyAdd.executeBatch();
        preparedStatementForPropertyUpdate.executeBatch();
        prepStatementForPropertyDelete.executeBatch();
    }

    private void addOrUpdateOIDCSpProperty(String preprocessedClientId,
                                           int spTenantId,
                                           Map<String, List<String>> spOIDCProperties,
                                           String propertyKey, String propertyValue,
                                           PreparedStatement preparedStatementForPropertyAdd,
                                           PreparedStatement preparedStatementForPropertyUpdate) throws SQLException {

        if (propertyAlreadyExists(spOIDCProperties, propertyKey)) {
            addToBatchForOIDCPropertyUpdate(preprocessedClientId, spTenantId, preparedStatementForPropertyUpdate,
                    propertyKey, propertyValue);
        } else {
            addToBatchForOIDCPropertyAdd(preprocessedClientId, spTenantId, preparedStatementForPropertyAdd,
                    propertyKey, propertyValue);
        }

    }

    private void addToBatchForOIDCPropertyAdd(String consumerKey,
                                              int tenantId,
                                              PreparedStatement preparedStatement,
                                              String propertyKey,
                                              String propertyValue) throws SQLException {

        preparedStatement.setInt(1, tenantId);
        preparedStatement.setString(2, consumerKey);
        preparedStatement.setString(3, propertyKey);
        preparedStatement.setString(4, propertyValue);
        preparedStatement.addBatch();
    }

    private void addToBatchForOIDCPropertyUpdate(String consumerKey,
                                                 int tenantId,
                                                 PreparedStatement preparedStatement,
                                                 String propertyKey,
                                                 String propertyValue) throws SQLException {

        preparedStatement.setString(1, propertyValue);
        preparedStatement.setInt(2, tenantId);
        preparedStatement.setString(3, consumerKey);
        preparedStatement.setString(4, propertyKey);
        preparedStatement.addBatch();
    }

    private void addToBatchForOIDCPropertyDelete(String consumerKey,
                                                 int tenantId,
                                                 PreparedStatement preparedStatement,
                                                 String propertyKey,
                                                 String propertyValue) throws SQLException {

        preparedStatement.setInt(1, tenantId);
        preparedStatement.setString(2, consumerKey);
        preparedStatement.setString(3, propertyKey);
        preparedStatement.setString(4, propertyValue);
        preparedStatement.addBatch();
    }

    private boolean propertyAlreadyExists(Map<String, List<String>> spOIDCProperties,
                                          String propertyKey) {

        return spOIDCProperties.containsKey(propertyKey);
    }

    private boolean validateUserForOwnerUpdate(OAuthAppDO oAuthAppDO) throws OAuthConsumerAppException {

        try {
            String userName;
            String usernameWithDomain = null;
            if (oAuthAppDO.getAppOwner() != null) {
                userName = oAuthAppDO.getAppOwner().getUserName();
                if (StringUtils.isEmpty(userName) || CarbonConstants.REGISTRY_SYSTEM_USERNAME.equals(userName)) {
                    return false;
                }
                String domainName = oAuthAppDO.getAppOwner().getUserStoreDomain();
                usernameWithDomain = UserCoreUtil.addDomainToName(userName, domainName);
            }

            UserRealm realm = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUserRealm();
            if (realm == null || StringUtils.isEmpty(usernameWithDomain)) {
                return false;
            }

            boolean isUserExist = realm.getUserStoreManager().isExistingUser(usernameWithDomain);
            if (!isUserExist) {
                throw new OAuthConsumerAppException("User validation failed for owner update in the application: " +
                        oAuthAppDO.getApplicationName() + " as user is not existing.");
            }
        } catch (UserStoreException e) {
            throw new OAuthConsumerAppException("User validation failed for owner update in the application: " +
                    oAuthAppDO.getApplicationName(), e);
        }
        return true;
    }

    private String getSqlQueryForUpdateConsumerApplication(boolean isUserValidForOwnerUpdate) {

        String sqlQuery = null;
        if (isUserValidForOwnerUpdate) {
            sqlQuery = SQLQueries.OAuthAppDAOSQLQueries.UPDATE_CONSUMER_APP_WITH_PKCE_AND_OWNER_UPDATE;
        } else {
            sqlQuery = SQLQueries.OAuthAppDAOSQLQueries.UPDATE_CONSUMER_APP_WITH_PKCE;
        }
        return sqlQuery;
    }

    private void setValuesToStatementWithPKCEAndOwnerUpdate(OAuthAppDO oauthAppDO,
                                                            PreparedStatement prepStmt)
            throws SQLException, IdentityOAuth2Exception {

        prepStmt.setString(4, oauthAppDO.isPkceMandatory() ? "1" : "0");
        prepStmt.setString(5, oauthAppDO.isPkceSupportPlain() ? "1" : "0");
        prepStmt.setLong(6, oauthAppDO.getUserAccessTokenExpiryTime());
        prepStmt.setLong(7, oauthAppDO.getApplicationAccessTokenExpiryTime());
        prepStmt.setLong(8, oauthAppDO.getRefreshTokenExpiryTime());
        prepStmt.setLong(9, oauthAppDO.getIdTokenExpiryTime());
        prepStmt.setString(10, oauthAppDO.getAppOwner().getUserName());
        prepStmt.setString(11, oauthAppDO.getAppOwner().getUserStoreDomain());
        prepStmt.setString(12, persistenceProcessor.getProcessedClientId(oauthAppDO.getOauthConsumerKey()));
    }

    private void setValuesToStatementWithPKCENoOwnerUpdate(OAuthAppDO oauthAppDO,
                                                           PreparedStatement prepStmt)
            throws SQLException, IdentityOAuth2Exception {

        prepStmt.setString(4, oauthAppDO.isPkceMandatory() ? "1" : "0");
        prepStmt.setString(5, oauthAppDO.isPkceSupportPlain() ? "1" : "0");
        prepStmt.setLong(6, oauthAppDO.getUserAccessTokenExpiryTime());
        prepStmt.setLong(7, oauthAppDO.getApplicationAccessTokenExpiryTime());
        prepStmt.setLong(8, oauthAppDO.getRefreshTokenExpiryTime());
        prepStmt.setLong(9, oauthAppDO.getIdTokenExpiryTime());
        prepStmt.setString(10, persistenceProcessor.getProcessedClientId(oauthAppDO.getOauthConsumerKey()));
    }

    private boolean isOIDCAudienceEnabled() {

        return OAuth2ServiceComponentHolder.isAudienceEnabled();
    }

    private List<String> getOIDCAudiences(String tenantDomain,
                                          String consumerKey) throws IdentityOAuth2Exception {

        List<String> audiences = new ArrayList<>();
        Connection connection = IdentityDatabaseUtil.getDBConnection();
        PreparedStatement prepStmt = null;
        ResultSet rSetAudiences = null;
        try {
            prepStmt = connection.prepareStatement(SQLQueries.OAuthAppDAOSQLQueries.GET_SP_OIDC_PROPERTY);
            prepStmt.setInt(1, IdentityTenantUtil.getTenantId(tenantDomain));
            prepStmt.setString(2, consumerKey);
            prepStmt.setString(3, OPENID_CONNECT_AUDIENCE);
            rSetAudiences = prepStmt.executeQuery();
            while (rSetAudiences.next()) {
                String audience = rSetAudiences.getString(1);
                if (audience != null) {
                    audiences.add(rSetAudiences.getString(1));
                }
            }
            connection.commit();
        } catch (SQLException e) {
            String errorMsg = "Error occurred while retrieving OIDC audiences for client ID: " + consumerKey +
                    " and tenant domain: " + tenantDomain;
            IdentityDatabaseUtil.rollbackTransaction(connection);
            throw new IdentityOAuth2Exception(errorMsg, e);
        } finally {
            IdentityDatabaseUtil.closeAllConnections(connection, rSetAudiences, prepStmt);
        }
        return audiences;
    }
}