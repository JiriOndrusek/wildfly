/*
 * Copyright 2018 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.as.test.integration.jsp;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.test.integration.common.HttpRequest;
import org.jboss.dmr.ModelNode;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.UrlAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;

/**
 * Test for issue https://issues.jboss.org/browse/WFLY-11008.
 */
@RunWith(Arquillian.class)
@RunAsClient
public class JspWithInvalidateAfterRedirectTestCase {
    private static final String DEPLOYMENT = "jsp-with-invalidate-after-redirect.war";

    private static final PathAddress LOG_FILE_ADDRESS = PathAddress.pathAddress()
            .append(SUBSYSTEM, "logging");

    @ArquillianResource
    private ManagementClient managementClient;

    @Deployment(name = DEPLOYMENT)
    public static WebArchive deployment() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, DEPLOYMENT);
        war.add(new UrlAsset(JspWithInvalidateAfterRedirectTestCase.class.getResource("jsp-with-invalidate-after-redirect_index.jsp")), "index.jsp");
        war.addAsWebInfResource(new UrlAsset(JspWithInvalidateAfterRedirectTestCase.class.getResource("jsp-with-invalidate-after-redirect_web.xml")), "web.xml");
        war.as(ZipExporter.class).exportTo(new File("/tmp/" + war.getName()), true);

        return war;
    }

    @Test
    @OperateOnDeployment(DEPLOYMENT)
    public void tesInvalidateAfterRedirect(@ArquillianResource URL url) throws Exception {
        //length of log before execution
        String sizeBefore = getLogLineCount(managementClient.getControllerClient());

        Exception ex = null;
        try {
            HttpRequest.get(url+"", 10, TimeUnit.SECONDS);
        } catch (Exception e) {
           ex = e;
        }
        Assert.assertNotNull("Redirect has to fail", ex);

        //wait some time to let session distribute
        Thread.sleep(2000);

        //size of log after execution
        String sizeAfter = getLogLineCount(managementClient.getControllerClient());

        long difference = Long.parseLong(sizeAfter) - Long.parseLong(sizeBefore);

        //validate, that there is no "UT005023: Exception handling request to ... is not in a valid state to be invoking cache operations on"
        List<ModelNode> lines = getLogs(managementClient.getControllerClient(), difference);
        for (ModelNode line : lines) {
            if (line.asString().contains("UT005023")) {
                Assert.fail("Invalidation after redirect throws error.");
            }
        }
    }

    private String getLogLineCount(final ModelControllerClient client) {
        ModelNode op = Util.createEmptyOperation("list-log-files", LOG_FILE_ADDRESS);
        ModelNode res = executeForResult(client, op);
        for (ModelNode node : res.asList()) {
            if ("server.log".equals(node.get("file-name").asString())) {
                return node.get("file-size").asString();
            }
        }
        return "0";
    }


    private List<ModelNode> getLogs(final ModelControllerClient client, long count) {
        // /subsystem=logging/log-file=server.log:read-log-file(lines=-1)
        ModelNode op = Util.createEmptyOperation("read-log-file", LOG_FILE_ADDRESS);
        op.get("lines").set(count);
        op.get("name").set("server.log");
        return executeForResult(client, op).asList();

    }


    private ModelNode executeForResult(final ModelControllerClient client, final ModelNode operation) {
        try {
            final ModelNode result = client.execute(operation);
            checkSuccessful(result);
            return result.get(ClientConstants.RESULT);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void checkSuccessful(final ModelNode result) {
        if (!ClientConstants.SUCCESS.equals(result.get(ClientConstants.OUTCOME).asString())) {
            throw new RuntimeException("operation has failed: " + result.get(ClientConstants.FAILURE_DESCRIPTION).toString());
        }
    }


}
