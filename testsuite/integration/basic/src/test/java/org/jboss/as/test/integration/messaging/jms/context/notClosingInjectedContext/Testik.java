/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.test.integration.messaging.jms.context.notClosingInjectedContext;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.test.integration.common.HttpRequest;
import org.jboss.as.test.integration.naming.local.simple.BeanWithBind;
import org.jboss.as.test.integration.naming.local.simple.ServletWithBind;
import org.jboss.as.test.shared.TestSuiteEnvironment;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.naming.java.permission.JndiPermission;

import java.io.IOException;
import java.net.SocketPermission;
import java.util.List;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;
import static org.jboss.as.test.shared.integration.ejb.security.PermissionUtils.createPermissionsXmlAsset;

/**
 * @author John Bailey
 */
@RunWith(Arquillian.class)
public class Testik {
    private static final Logger LOGGER = Logger.getLogger(Testik.class);

    @Deployment
    public static Archive<?> deploy() {
        final WebArchive war = ShrinkWrap.create(WebArchive.class, "test.war");
        war.addClasses(HttpRequest.class, BeanWithBind.class, ServletWithBind.class);
        war.addAsManifestResource(createPermissionsXmlAsset(
                new JndiPermission("global", "listBindings"),
                new JndiPermission("jboss", "listBindings"),
                new JndiPermission("jboss/exported", "listBindings"),
                new JndiPermission("/test", "bind"),
                new JndiPermission("/web-test", "bind"),
                new JndiPermission("jboss/test", "bind"),
                new JndiPermission("jboss/web-test", "bind"),
                // org.jboss.as.test.integration.common.HttpRequest needs the following permissions
                new RuntimePermission("modifyThread"),
                new SocketPermission(TestSuiteEnvironment.getHttpAddress() + ":" + TestSuiteEnvironment.getHttpPort(), "connect,resolve")),
                "permissions.xml")
                .addAsManifestResource(new StringAsset("Dependencies: org.jboss.as.controller\n"), "MANIFEST.MF");
        return war;
    }

    @ArquillianResource
    private ManagementClient managementClient;


    @Test
    public void test() throws Exception {
        List<ModelNode> nodes = getLogs(managementClient.getControllerClient());
        boolean firstMessageFound = false;
        boolean secondMessageFound = false;
        int i =0;
        for (ModelNode node : nodes) {
            String line = node.asString();
            System.out.println(line);
            i++;
        }
        LOGGER.error("------------------------------" + i);
        Assert.assertEquals(-1, i);
    }


    private static final PathAddress LOG_FILE_ADDRESS = PathAddress.pathAddress()
            .append(SUBSYSTEM, "logging")
            .append("log-file", "server.log");
     List<ModelNode> getLogs(final ModelControllerClient client) {
        // /subsystem=logging/log-file=server.log:read-log-file(lines=-1)
        ModelNode op = Util.createEmptyOperation("read-log-file", LOG_FILE_ADDRESS);
        op.get("lines").set(-1);
        return executeForResult(client, op).asList();

    }


     ModelNode executeForResult(final ModelControllerClient client, final ModelNode operation) {
        try {
            final ModelNode result = client.execute(operation);
            checkSuccessful(result, operation);
            return result.get(ClientConstants.RESULT);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void checkSuccessful(final ModelNode result, final ModelNode operation) {
        if (!ClientConstants.SUCCESS.equals(result.get(ClientConstants.OUTCOME).asString())) {
//            LOGGER.error("Operation " + operation + " did not succeed. Result was " + result);
            throw new RuntimeException("operation has failed: " + result.get(ClientConstants.FAILURE_DESCRIPTION).toString());
        }
    }


}
