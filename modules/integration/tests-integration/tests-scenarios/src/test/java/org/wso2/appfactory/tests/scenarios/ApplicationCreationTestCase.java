/*
 * Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *   WSO2 Inc. licenses this file to you under the Apache License,
 *   Version 2.0 (the "License"); you may not use this file except
 *   in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an
 *   "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *   KIND, either express or implied.  See the License for the
 *   specific language governing permissions and limitations
 *   under the License.
 */
package org.wso2.appfactory.tests.scenarios;

import com.google.gson.JsonArray;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;
import org.wso2.appfactory.integration.test.utils.AFConstants;
import org.wso2.appfactory.integration.test.utils.AFDefaultDataPopulator;
import org.wso2.appfactory.integration.test.utils.AFIntegrationTest;
import org.wso2.appfactory.integration.test.utils.AFIntegrationTestException;
import org.wso2.appfactory.integration.test.utils.AFIntegrationTestUtils;
import org.wso2.appfactory.integration.test.utils.rest.ApplicationClient;
import org.wso2.carbon.automation.engine.annotations.ExecutionEnvironment;
import org.wso2.carbon.automation.engine.annotations.SetEnvironment;

public class ApplicationCreationTestCase extends AFIntegrationTest {
    private static final Log log = LogFactory.getLog(ApplicationCreationTestCase.class);
    public static final String APP_TYPE_WAR = "war";
    private static final String INITIAL_STAGE = "Development";
    private ApplicationClient appMgtRestClient;
    @BeforeClass(alwaysRun = true)
    public void setEnvironment() throws Exception {
        appMgtRestClient = new ApplicationClient(AFserverUrl, defaultAdmin, defaultAdminPassword);
    }

    @SetEnvironment(executionEnvironments = {ExecutionEnvironment.PLATFORM})
    @Test(description = "Sample test method")
    public void testWarAppType() throws Exception {
        String appName = "foo_" + APP_TYPE_WAR;
        log.info("Creating application of type :" + APP_TYPE_WAR + " with name :" + appName);
        createApplication(appName, appName, appName, APP_TYPE_WAR);
        log.info("Application creation is completed.");
    }

    @SetEnvironment(executionEnvironments = {ExecutionEnvironment.PLATFORM})
    @Test(description = "Get applications of user  <used in Dev Studio>")
    public void testGetApplicationsOfUser() throws AFIntegrationTestException{
        JsonArray resultArray =  appMgtRestClient.getApplicationsOfUser(defaultAdmin);
        boolean isAssert = false;
        if (resultArray.size()!=0){
            isAssert=true;
        }
        Assert.assertEquals(isAssert,true,"Get applications of user failed.");
    }

    @SetEnvironment(executionEnvironments = {ExecutionEnvironment.PLATFORM})
    @Test(description = "Get application versions in stage  <used in Dev Studio>")
    public void testGetAppVersionsInStage() throws AFIntegrationTestException{
        JsonArray resultArray =  appMgtRestClient.getAppVersionsInStage(defaultAdmin,INITIAL_STAGE , defaultAppKey);
        boolean isAssert = false;
        if (resultArray.size()!=0){
            isAssert=true;
        }
        Assert.assertEquals(isAssert,true,"Get application versions in stage failed.");
    }


    @AfterClass(alwaysRun = true)
    public void destroy() throws Exception {
        super.cleanup();
    }

    protected void createApplication(String applicationName, String applicationKey, String applicationDescription,
                                     String applicationType) throws Exception {

        if (appMgtRestClient.isAppNameAlreadyAvailable(applicationName) &&
            appMgtRestClient.isApplicationKeyAvailable(applicationKey)) {
            appMgtRestClient.createNewApplication(applicationName, applicationKey, applicationType,
                                                  defaultAdmin, applicationDescription);
        }

        // Wait till Create Application completion
        AFDefaultDataPopulator populator = new AFDefaultDataPopulator();
        populator.waitUntilApplicationCreationCompletes(5000L, 5, defaultAdmin, defaultAdminPassword,
                                                        applicationKey, applicationName);
    }
}
