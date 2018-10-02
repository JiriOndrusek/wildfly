/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
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
import org.jboss.as.test.integration.messaging.jms.context.notClosingInjectedContext.auxiliary.Mdb;
import org.jboss.as.test.integration.messaging.jms.context.notClosingInjectedContext.auxiliary.StartUp;
import org.jboss.as.test.shared.TimeoutUtil;
import org.jboss.logging.Logger;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.annotation.Resource;
import javax.jms.ConnectionFactory;
import javax.jms.JMSConsumer;
import javax.jms.JMSContext;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.Queue;
import java.io.File;
import java.util.PropertyPermission;

import static org.jboss.as.test.shared.TimeoutUtil.adjust;
import static org.jboss.as.test.shared.integration.ejb.security.PermissionUtils.createPermissionsXmlAsset;

/**
 * Test for issue https://issues.jboss.org/browse/WFLY-10531 (based on reproducer created by Gunter Zeilinger <gunterze@gmail.com>.
 *
 * 500 messages should be send to mdb and each of them should be received in verify queue.
 * If error is still valid, there will be exceptions like: IJ000453: Unable to get managed connection for java:/JmsXA
 *
 * @author Jiri Ondrusek <jondruse@redhat.com>
 */
@RunWith(Arquillian.class)
public class NotClosingInjectedContextTestCase {
    private static final Logger LOGGER = Logger.getLogger(NotClosingInjectedContextTestCase.class);

    @Resource(mappedName = "java:/JmsXA")
    private ConnectionFactory factory;

    @Resource(mappedName = Mdb.JNDI_NAME)
    private Queue queue;

    @Resource(mappedName = Mdb.JNDI_VERIFY_NAME)
    private Queue queueVerify;

    @Deployment
    public static WebArchive createTestArchive() {
        WebArchive archive = ShrinkWrap.create(WebArchive.class, "NotClosingInjectedContextTestCase.war")
                .addPackage(StartUp.class.getPackage())
                .addAsManifestResource(EmptyAsset.INSTANCE, "beans.xml")
                .addClass(TimeoutUtil.class)
                .addAsResource(createPermissionsXmlAsset(new PropertyPermission("ts.timeout.factor", "read")), "META-INF/jboss-permissions.xml")
                .addAsResource("org/jboss/as/test/integration/messaging/jms/context/notClosingInjectedContext/auxiliary/persistence.xml", "META-INF/persistence.xml");

        return archive;
    }

    @After
    public void tearDown() throws JMSException {
        // drain the queue to remove any pending messages from it
        try(JMSContext context = factory.createContext()) {
            JMSConsumer consumer = context.createConsumer(queue);
            Message m;
            do {
                m = consumer.receiveNoWait();
            }
            while (m != null);

            consumer = context.createConsumer(queueVerify);
            do {
                m = consumer.receiveNoWait();
            }
            while (m != null);
        }
    }

    @Test
    public void testExhaustContexts() throws Exception {


        int i = 0;
        try (JMSContext context = factory.createContext(); JMSConsumer consumer = context.createConsumer(queueVerify)) {
            while(true) {
                String t = consumer.receiveBody(String.class, adjust(2000));
                if(t == null) {
                    break;
                }
                LOGGER.info("Received messae:" + i);
                i++;
            }


        }
        Assert.assertEquals(500, i);
    }


}