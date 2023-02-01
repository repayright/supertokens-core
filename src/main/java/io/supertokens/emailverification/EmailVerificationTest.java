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

package io.supertokens.emailverification;

import io.supertokens.Main;
import io.supertokens.ResourceDistributor;
import io.supertokens.config.Config;

public class EmailVerificationTest extends ResourceDistributor.SingletonResource {
    private static final String RESOURCE_ID = "io.supertokens.emailverification.EmailVerificationTest";
    private Long emailVerificationTokenLifetimeMS = null;
    private Main main;

    private EmailVerificationTest(Main main) {
        this.main = main;
    }

    public static EmailVerificationTest getInstance(Main main) {
        ResourceDistributor.SingletonResource resource = main.getResourceDistributor().getResource(RESOURCE_ID);
        if (resource == null) {
            resource = main.getResourceDistributor().setResource(RESOURCE_ID, new EmailVerificationTest(main));
        }
        return (EmailVerificationTest) resource;
    }

    public void setEmailVerificationTokenLifetime(long intervalMS) {
        this.emailVerificationTokenLifetimeMS = intervalMS;
    }

    public long getEmailVerificationTokenLifetime() {
        if (this.emailVerificationTokenLifetimeMS == null) {
            return Config.getConfig(null, null, this.main).getEmailVerificationTokenLifetime();
        }
        return this.emailVerificationTokenLifetimeMS;
    }
}
