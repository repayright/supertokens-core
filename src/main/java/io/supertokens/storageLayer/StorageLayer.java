/*
 *    Copyright (c) 2020, VRAI Labs and/or its affiliates. All rights reserved.
 *
 *    This software is licensed under the Apache License, Version 2.0 (the
 *    "License") as published by the Apache Software Foundation.
 *
 *    You may not use this file except in compliance with the License. You may
 *    obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package io.supertokens.storageLayer;

import com.google.gson.JsonObject;
import io.supertokens.*;
import io.supertokens.cliOptions.CLIOptions;
import io.supertokens.config.Config;
import io.supertokens.exceptions.QuitProgramException;
import io.supertokens.inmemorydb.Start;
import io.supertokens.output.Logging;
import io.supertokens.pluginInterface.LOG_LEVEL;
import io.supertokens.pluginInterface.Storage;
import io.supertokens.pluginInterface.authRecipe.AuthRecipeStorage;
import io.supertokens.pluginInterface.emailpassword.exceptions.UnknownUserIdException;
import io.supertokens.pluginInterface.exceptions.DbInitException;
import io.supertokens.pluginInterface.exceptions.InvalidConfigException;
import io.supertokens.pluginInterface.exceptions.StorageQueryException;
import io.supertokens.pluginInterface.multitenancy.*;
import io.supertokens.pluginInterface.multitenancy.exceptions.TenantOrAppNotFoundException;
import io.supertokens.pluginInterface.useridmapping.UserIdMapping;
import io.supertokens.useridmapping.UserIdType;
import jakarta.servlet.ServletException;
import org.jetbrains.annotations.TestOnly;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

public class StorageLayer extends ResourceDistributor.SingletonResource {

    public static final String RESOURCE_KEY = "io.supertokens.storageLayer.StorageLayer";
    private final Storage storage;
    private static URLClassLoader ucl = null;

    public Storage getUnderlyingStorage() {
        return storage;
    }

    public static Storage getNewStorageInstance(Main main, JsonObject config, TenantIdentifier tenantIdentifier, boolean doNotLog) throws InvalidConfigException {
        Storage result;
        if (StorageLayer.ucl == null) {
            result = new Start(main);
        } else {
            Storage storageLayer = null;
            ServiceLoader<Storage> sl = ServiceLoader.load(Storage.class, ucl);
            for (Storage plugin : sl) {
                if (storageLayer == null) {
                    storageLayer = plugin;
                } else {
                    throw new QuitProgramException(
                            "Multiple database plugins found. Please make sure that just one plugin is in the "
                                    + "/plugin" + " "
                                    + "folder of the installation. Alternatively, please redownload and install "
                                    + "SuperTokens" + ".");
                }
            }
            if (storageLayer != null && !main.isForceInMemoryDB()
                    && (storageLayer.canBeUsed(config) || CLIOptions.get(main).isForceNoInMemoryDB())) {
                result = storageLayer;
            } else {
                result = new Start(main);
            }
        }
        result.constructor(main.getProcessId(), Main.makeConsolePrintSilent, Main.isTesting);

        Set<LOG_LEVEL> logLevels = null;
        if (doNotLog) {
            logLevels = new HashSet<>();
        } else {
            logLevels = Config.getBaseConfig(main).getLogLevels(main);
        }
        // this is intentionally null, null below cause log levels is per core and not per tenant anyway
        result.loadConfig(config, logLevels, tenantIdentifier);
        return result;
    }

    private StorageLayer(Storage storage) {
        this.storage = storage;
    }

    private StorageLayer(Main main, String pluginFolderPath, JsonObject configJson, TenantIdentifier tenantIdentifier)
            throws MalformedURLException, InvalidConfigException {
        Logging.info(main, tenantIdentifier, "Loading storage layer.", true);
        File loc = new File(pluginFolderPath);

        File[] flist = loc.listFiles(file -> file.getPath().toLowerCase().endsWith(".jar"));

        if (flist != null) {
            URL[] urls = new URL[flist.length];
            for (int i = 0; i < flist.length; i++) {
                urls[i] = flist[i].toURI().toURL();
            }
            if (StorageLayer.ucl == null) {
                // we have this as a static variable because
                // in prod, this is loaded just once anyway.
                // During testing, we just want to load the jars
                // once too cause the JARs don't change across tests either.
                StorageLayer.ucl = new URLClassLoader(urls);
            }
        }

        this.storage = getNewStorageInstance(main, configJson, tenantIdentifier, false);

        if (this.storage instanceof Start) {
            Logging.info(main, TenantIdentifier.BASE_TENANT, "Using in memory storage.", true);
        }
    }

    public static void close(Main main) {
        Map<ResourceDistributor.KeyClass, ResourceDistributor.SingletonResource> resources =
                main.getResourceDistributor()
                        .getAllResourcesWithResourceKey(RESOURCE_KEY);
        for (ResourceDistributor.SingletonResource resource : resources.values()) {
            ((StorageLayer) resource).storage.close();
        }
    }

    public static void stopLogging(Main main) {
        Map<ResourceDistributor.KeyClass, ResourceDistributor.SingletonResource> resources =
                main.getResourceDistributor()
                        .getAllResourcesWithResourceKey(RESOURCE_KEY);
        for (ResourceDistributor.SingletonResource resource : resources.values()) {
            ((StorageLayer) resource).storage.stopLogging();
        }
    }

    @TestOnly
    public static void deleteAllInformation(Main main) throws StorageQueryException {
        Map<ResourceDistributor.KeyClass, ResourceDistributor.SingletonResource> resources =
                main.getResourceDistributor()
                        .getAllResourcesWithResourceKey(RESOURCE_KEY);
        Set<Storage> uniqueStorages = new HashSet<>();
        for (ResourceDistributor.SingletonResource resource : resources.values()) {
            uniqueStorages.add(((StorageLayer) resource).storage);
        }

        for (Storage storage : uniqueStorages) {
            storage.deleteAllInformation();
        }
    }

    @TestOnly
    public static void close() {
        // TODO: remove this function and remove all the places it's being used.
    }

    @TestOnly
    public static void clearURLClassLoader() {
        /*
         * This is needed for PluginTests where we want to try and load from the plugin directory
         * again and again. If we do not close the static URLCLassLoader before, those tests will fail
         *
         * Also note that closing it doesn't actually remove it from memory (strange..). But we do it anyway
         */
        if (StorageLayer.ucl != null) {
            try {
                StorageLayer.ucl.close();
            } catch (IOException ignored) {
            }
            StorageLayer.ucl = null;
        }
    }

    private static StorageLayer getInstance(TenantIdentifier tenantIdentifier, Main main)
            throws TenantOrAppNotFoundException {
        return (StorageLayer) main.getResourceDistributor().getResource(tenantIdentifier, RESOURCE_KEY);
    }

    public static void initPrimary(Main main, String pluginFolderPath, JsonObject configJson)
            throws MalformedURLException, InvalidConfigException {
        main.getResourceDistributor().setResource(new TenantIdentifier(null, null, null), RESOURCE_KEY,
                new StorageLayer(main, pluginFolderPath, configJson, TenantIdentifier.BASE_TENANT));
    }

    public static void loadAllTenantStorage(Main main, TenantConfig[] tenants)
            throws InvalidConfigException, IOException {
        // We decided not to include tenantsThatChanged in this function because we do not want to reload the storage
        // when the db config has not change. And when db config has changed, it results in a
        // different userPoolId + connectionPoolId, which in turn results in a new storage instance

        ProcessState.getInstance(main).addState(ProcessState.PROCESS_STATE.LOADING_ALL_TENANT_STORAGE, null);

        Map<ResourceDistributor.KeyClass, JsonObject> normalisedConfigs = Config.getNormalisedConfigsForAllTenants(
                tenants,
                Config.getBaseConfigAsJsonObject(main));

        Map<ResourceDistributor.KeyClass, Storage> resourceKeyToStorageMap = new HashMap<>();
        {
            Map<String, Storage> idToStorageMap = new HashMap<>();
            for (ResourceDistributor.KeyClass key : normalisedConfigs.keySet()) {
                // setting doNotLog to true so that plugin loading is not logged here
                Storage storage = StorageLayer.getNewStorageInstance(main, normalisedConfigs.get(key), key.getTenantIdentifier(), true);
                String userPoolId = storage.getUserPoolId();
                String connectionPoolId = storage.getConnectionPoolId();
                String uniqueId = userPoolId + "~" + connectionPoolId;
                if (idToStorageMap.get(uniqueId) != null) {
                    // this means there already exists a storage object that can be reused
                    // for this tenant
                    resourceKeyToStorageMap.put(key, idToStorageMap.get(uniqueId));
                } else {
                    idToStorageMap.put(uniqueId, storage);
                    resourceKeyToStorageMap.put(key, storage);
                }
            }
        }

        // at this point, we have made sure that all the configs are fine and that the storage
        // objects are shared across tenants based on the config of each tenant.

        // now we loop through existing storage objects in the main resource distributor and reuse them
        // if the unique ID is the same as the storage objects created above.
        try {
            main.getResourceDistributor().withResourceDistributorLock(() -> {
                Map<ResourceDistributor.KeyClass, ResourceDistributor.SingletonResource> existingStorageMap =
                        main.getResourceDistributor()
                                .getAllResourcesWithResourceKey(RESOURCE_KEY);
                Map<String, StorageLayer> idToExistingStorageLayerMap = new HashMap<>();
                for (ResourceDistributor.SingletonResource resource : existingStorageMap.values()) {
                    StorageLayer currStorageLayer = (StorageLayer) resource;
                    String userPoolId = currStorageLayer.storage.getUserPoolId();
                    String connectionPoolId = currStorageLayer.storage.getConnectionPoolId();
                    String uniqueId = userPoolId + "~" + connectionPoolId;
                    idToExistingStorageLayerMap.put(uniqueId, currStorageLayer);
                }
                main.getResourceDistributor().clearAllResourcesWithResourceKey(RESOURCE_KEY);

                for (ResourceDistributor.KeyClass key : resourceKeyToStorageMap.keySet()) {
                    Storage currStorage = resourceKeyToStorageMap.get(key);
                    String userPoolId = currStorage.getUserPoolId();
                    String connectionPoolId = currStorage.getConnectionPoolId();
                    String uniqueId = userPoolId + "~" + connectionPoolId;
                    if (idToExistingStorageLayerMap.containsKey(uniqueId)) {
                        // we reuse the existing storage layer
                        resourceKeyToStorageMap.put(key, idToExistingStorageLayerMap.get(uniqueId).storage);
                    }

                    resourceKeyToStorageMap.get(key).setLogLevels(Config.getBaseConfig(main).getLogLevels(main));

                    main.getResourceDistributor().setResource(key.getTenantIdentifier(), RESOURCE_KEY,
                            new StorageLayer(resourceKeyToStorageMap.get(key)));
                }

                // TODO: should the below code be outside of this locked code cause it takes time
                //  and any other thread that will want access to the resource distributor will have
                //  to wait for this?
                // we remove storage layers that are no longer being used
                for (ResourceDistributor.KeyClass key : existingStorageMap.keySet()) {
                    try {
                        if (((StorageLayer) main.getResourceDistributor()
                                .getResource(key.getTenantIdentifier(), RESOURCE_KEY)).storage !=
                                ((StorageLayer) existingStorageMap.get(key)).storage) {
                            // this means that this storage layer is no longer being used, so we close it
                            ((StorageLayer) existingStorageMap.get(key)).storage.close();
                            ((StorageLayer) existingStorageMap.get(key)).storage.stopLogging();
                        }
                    } catch (TenantOrAppNotFoundException e) {
                        throw new IllegalStateException(e);
                    }
                }

                // we call init on all the newly saved storage objects.
                Map<ResourceDistributor.KeyClass, ResourceDistributor.SingletonResource> resources =
                        main.getResourceDistributor()
                                .getAllResourcesWithResourceKey(RESOURCE_KEY);
                for (ResourceDistributor.SingletonResource resource : resources.values()) {
                    try {
                        ((StorageLayer) resource).storage.initStorage(false);
                        ((StorageLayer) resource).storage.initFileLogging(
                                Config.getBaseConfig(main).getInfoLogPath(main),
                                Config.getBaseConfig(main).getErrorLogPath(main));
                    } catch (DbInitException e) {

                        Logging.error(main, TenantIdentifier.BASE_TENANT, e.getMessage(), false, e);
                        // we ignore any exceptions from db here cause it's not the base tenant's db that
                        // would throw and only tenants belonging to a specific tenant / app. In this case,
                        // we still want other tenants to continue to work
                    }
                }
                return null;
            });
        } catch (ResourceDistributor.FuncException e) {
            throw new RuntimeException(e);
        }
    }

    public static Storage getBaseStorage(Main main) {
        try {
            return getInstance(new TenantIdentifier(null, null, null), main).storage;
        } catch (TenantOrAppNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Storage getStorage(TenantIdentifier tenantIdentifier, Main main)
            throws TenantOrAppNotFoundException {
        return getInstance(tenantIdentifier, main).storage;
    }

    @TestOnly
    public static Storage getStorage(Main main) {
        try {
            return getStorage(new TenantIdentifier(null, null, null), main);
        } catch (TenantOrAppNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    // This function intentionally doesn't take connectionUriDomain and tenantId
    // cause the data for this is only going to be in the primary db of the core.
    public static MultitenancyStorage getMultitenancyStorage(Main main) {
        try {
            return (MultitenancyStorage) getInstance(new TenantIdentifier(null, null, null), main).storage;
        } catch (TenantOrAppNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean isInMemDb(Main main) {
        try {
            return getInstance(new TenantIdentifier(null, null, null), main).storage instanceof Start;
        } catch (TenantOrAppNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    @TestOnly
    public static boolean hasMultipleUserPools(Main main) {
        List<ResourceDistributor.KeyClass> result = new ArrayList<ResourceDistributor.KeyClass>();
        String usedIds = "";

        Map<ResourceDistributor.KeyClass, ResourceDistributor.SingletonResource> resources =
                main.getResourceDistributor()
                        .getAllResourcesWithResourceKey(RESOURCE_KEY);
        for (ResourceDistributor.KeyClass key : resources.keySet()) {
            Storage storage = ((StorageLayer) resources.get(key)).storage;
            if (usedIds.equals("")) {
                usedIds = storage.getUserPoolId();
            }
            if (usedIds.equals(storage.getUserPoolId())) {
                continue;
            }
            return true;
        }
        return false;
    }

    public static List<List<TenantIdentifier>> getTenantsWithUniqueUserPoolId(Main main) {
        List<List<TenantIdentifier>> result = new ArrayList<>();
        Map<String, List<TenantIdentifier>> uniquePoolList = new HashMap<>();

        Map<ResourceDistributor.KeyClass, ResourceDistributor.SingletonResource> resources =
                main.getResourceDistributor()
                        .getAllResourcesWithResourceKey(RESOURCE_KEY);
        for (ResourceDistributor.KeyClass key : resources.keySet()) {
            Storage storage = ((StorageLayer) resources.get(key)).storage;
            if (uniquePoolList.get(storage.getUserPoolId()) != null) {
                uniquePoolList.get(storage.getUserPoolId()).add(key.getTenantIdentifier());
            } else {
                uniquePoolList.put(storage.getUserPoolId(), new ArrayList<>());
                uniquePoolList.get(storage.getUserPoolId()).add(key.getTenantIdentifier());
            }
        }
        for (String s : uniquePoolList.keySet()) {
            result.add(uniquePoolList.get(s));
        }
        return result;
    }

    public static Storage[] getStoragesForApp(Main main, AppIdentifier appIdentifier) {
        Map<String, Storage> userPoolToStorage = new HashMap<>();

        Map<ResourceDistributor.KeyClass, ResourceDistributor.SingletonResource> resources =
                main.getResourceDistributor()
                        .getAllResourcesWithResourceKey(RESOURCE_KEY);
        for (ResourceDistributor.KeyClass key : resources.keySet()) {
            Storage storage = ((StorageLayer) resources.get(key)).storage;
            if (key.getTenantIdentifier().toAppIdentifier().equals(appIdentifier)) {
                userPoolToStorage.put(storage.getUserPoolId(), storage);
            }
        }
        return userPoolToStorage.values().toArray(new Storage[0]);
    }

    public static TenantIdentifierWithStorageAndUserIdMapping getTenantIdentifierWithStorageAndUserIdMappingForUser(
            Main main, TenantIdentifier tenantIdentifier, String userId, UserIdType userIdType)
            throws StorageQueryException, TenantOrAppNotFoundException, UnknownUserIdException {
        Storage storage = getStorage(tenantIdentifier, main);
        TenantIdentifierWithStorage tenantIdentifierWithStorage = tenantIdentifier.withStorage(storage);

        UserIdMapping mapping = io.supertokens.useridmapping.UserIdMapping.getUserIdMapping(
                tenantIdentifierWithStorage.toAppIdentifierWithStorage(), userId, userIdType);

        if (mapping != null) {
            return new TenantIdentifierWithStorageAndUserIdMapping(tenantIdentifierWithStorage, mapping);
        }

        if (userIdType != UserIdType.EXTERNAL
                && ((AuthRecipeStorage) storage).doesUserIdExist(tenantIdentifier.toAppIdentifier(), userId)) {
            return new TenantIdentifierWithStorageAndUserIdMapping(
                    tenantIdentifierWithStorage, null);
        }
        if (userIdType != UserIdType.SUPERTOKENS) {
            try {
                io.supertokens.useridmapping.UserIdMapping.assertThatUserIdIsNotBeingUsedInNonAuthRecipes(
                        tenantIdentifierWithStorage.toAppIdentifierWithStorage(), userId);
            } catch (ServletException e) {
                // this means that the userId is being used for a non auth recipe.
                return new TenantIdentifierWithStorageAndUserIdMapping(
                        tenantIdentifierWithStorage, null);
            }
        }

        throw new UnknownUserIdException();
    }

    public static AppIdentifierWithStorageAndUserIdMapping getAppIdentifierWithStorageAndUserIdMappingForUserWithPriorityForTenantStorage(
            Main main, AppIdentifier appIdentifier, Storage priorityStorage, String userId,
            UserIdType userIdType) throws StorageQueryException, TenantOrAppNotFoundException, UnknownUserIdException {

        Storage[] storages = getStoragesForApp(main, appIdentifier);

        if (storages.length == 0) {
            throw new TenantOrAppNotFoundException(appIdentifier);
        }

        // We look for userId in the priorityStorage first just in case multiple storages have the mapping, we
        // return the mapping from the storage of the tenant from which the request came from.
        {
            UserIdMapping mapping = io.supertokens.useridmapping.UserIdMapping.getUserIdMapping(
                    appIdentifier.withStorage(priorityStorage),
                    userId, userIdType);

            if (mapping != null) {
                AppIdentifierWithStorage appIdentifierWithStorage = appIdentifier.withStorage(priorityStorage);
                return new AppIdentifierWithStorageAndUserIdMapping(appIdentifierWithStorage, mapping);
            }

            if (userIdType != UserIdType.EXTERNAL
                    && ((AuthRecipeStorage) priorityStorage).doesUserIdExist(appIdentifier, userId)) {
                AppIdentifierWithStorage appIdentifierWithStorage = appIdentifier.withStorage(priorityStorage);
                return new AppIdentifierWithStorageAndUserIdMapping(appIdentifierWithStorage, null);
            }
            if (userIdType != UserIdType.SUPERTOKENS) {
                AppIdentifierWithStorage appIdentifierWithStorage = appIdentifier.withStorage(priorityStorage);
                try {
                    io.supertokens.useridmapping.UserIdMapping.assertThatUserIdIsNotBeingUsedInNonAuthRecipes(
                            appIdentifierWithStorage, userId);
                } catch (ServletException e) {
                    // this means that the userId is being used for a non auth recipe.
                    return new AppIdentifierWithStorageAndUserIdMapping(appIdentifierWithStorage, null);
                }
            }
        }

        for (Storage storage : storages) {
            if (storage == priorityStorage) {
                continue; // Already checked previously
            }

            UserIdMapping mapping = io.supertokens.useridmapping.UserIdMapping.getUserIdMapping(
                    appIdentifier.withStorage(storage),
                    userId, userIdType);

            if (mapping != null) {
                AppIdentifierWithStorage appIdentifierWithStorage = appIdentifier.withStorage(storage);
                return new AppIdentifierWithStorageAndUserIdMapping(appIdentifierWithStorage, mapping);
            }

            if (userIdType != UserIdType.EXTERNAL
                    && ((AuthRecipeStorage) storage).doesUserIdExist(appIdentifier, userId)) {
                AppIdentifierWithStorage appIdentifierWithStorage = appIdentifier.withStorage(storage);
                return new AppIdentifierWithStorageAndUserIdMapping(appIdentifierWithStorage, null);
            }
            if (userIdType != UserIdType.SUPERTOKENS) {
                AppIdentifierWithStorage appIdentifierWithStorage = appIdentifier.withStorage(storage);
                try {
                    io.supertokens.useridmapping.UserIdMapping.assertThatUserIdIsNotBeingUsedInNonAuthRecipes(
                            appIdentifierWithStorage, userId);
                } catch (ServletException e) {
                    // this means that the userId is being used for a non auth recipe.
                    return new AppIdentifierWithStorageAndUserIdMapping(appIdentifierWithStorage, null);
                }
            }
        }

        throw new UnknownUserIdException();
    }
}
